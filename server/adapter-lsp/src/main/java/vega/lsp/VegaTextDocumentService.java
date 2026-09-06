package vega.lsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensDelta;
import org.eclipse.lsp4j.SemanticTokensDeltaParams;
import org.eclipse.lsp4j.SemanticTokensEdit;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import vega.core.document.Document;
import vega.core.document.Edit;
import vega.core.highlight.ChangedRange;
import vega.core.highlight.HighlightService;
import vega.core.highlight.Highlighter;
import vega.core.highlight.SemanticTokenEncoder;
import vega.core.highlight.Token;
import vega.core.highlight.TokenDelta;
import vega.core.highlight.TokenDiffer;
import vega.core.obs.Correlation;
import vega.core.port.CancellationToken;
import vega.core.port.CancelledException;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;
import vega.core.port.SyntaxParserPort;

/**
 * Document lifecycle and semantic tokens.
 *
 * <p>Parsing starts at {@code didOpen} and never before. Trees are adapter-owned native resources,
 * so the previous tree is closed whenever it is replaced or the document closes; letting them
 * accumulate leaks off-heap memory the JVM's own pressure signals never account for.
 */
public class VegaTextDocumentService implements TextDocumentService {

    /**
     * How many past token results to keep per server for delta requests.
     *
     * <p>Bounded on purpose: an unbounded cache of full token arrays for a 50,000-line file is a
     * slow memory leak. The protocol already requires clients to cope with an evicted id, so the
     * cost of a small cache is an occasional full result rather than a correctness problem.
     */
    private static final int RESULT_CACHE_SIZE = 8;

    private final vega.core.document.DocumentService documents;
    private final SyntaxParserPort parser;
    private final SemanticTokensHandler semanticTokens;
    private final ProgressReporter progress;
    private final HighlightService highlights;

    private final Map<String, ParsedTree> trees = new HashMap<>();
    private final Map<String, Integer> lastClientVersion = new HashMap<>();

    /** resultId to the token array sent under it, most recent last. */
    private final Map<String, int[]> sentResults =
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
                    return size() > RESULT_CACHE_SIZE;
                }
            };

    private final Map<String, String> latestResultId = new HashMap<>();

    public VegaTextDocumentService(
            vega.core.document.DocumentService documents,
            SyntaxParserPort parser,
            SemanticTokensHandler semanticTokens,
            ProgressReporter progress,
            HighlightService highlights) {
        this.documents = documents;
        this.parser = parser;
        this.semanticTokens = semanticTokens;
        this.progress = progress;
        this.highlights = highlights;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        try (var scope = Correlation.beginScope(Correlation.newId())) {
            String uri = params.getTextDocument().getUri();
            documents.openFromEditor(uri, params.getTextDocument().getText());
            ParsedTree tree =
                    progress.around(
                            "Parsing " + uri.substring(uri.lastIndexOf('/') + 1),
                            () -> parser.parse(params.getTextDocument().getText(), CancellationToken.never()));
            replaceTree(uri, tree);
            lastClientVersion.put(uri, params.getTextDocument().getVersion());
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        try (var scope = Correlation.beginScope(Correlation.newId())) {
            String uri = params.getTextDocument().getUri();
            int incoming = params.getTextDocument().getVersion();
            Integer known = lastClientVersion.get(uri);

            // Strict version ordering. A duplicated or replayed notification would otherwise apply
            // its edit a second time and desynchronise the mirror permanently — and silently, since
            // every later result would still look structurally valid.
            if (known != null && incoming <= known) {
                return;
            }

            ParsedTree previous = trees.get(uri);
            if (previous == null) {
                return;
            }

            ChangedRange repainted = null;
            for (TextDocumentContentChangeEvent event : params.getContentChanges()) {
                Document current = documents.current(uri);
                Edit edit = toEdit(current, event);

                Document updated = documents.applyEdit(uri, edit);
                try {
                    // Applying an accepted edit is not abandonable — the mirror must move with the
                    // editor — so re-analysis of it runs to completion here. Cancellation belongs to
                    // requests, which US2's cancellation test covers separately.
                    HighlightService.Reanalysis reanalysis =
                            highlights.reanalyse(
                                    previous, updated, edit, updated.content(), CancellationToken.never());
                    replaceTree(uri, reanalysis.tree());
                    previous = reanalysis.tree();
                    repainted = reanalysis.window();
                } catch (CancelledException ignored) {
                    // Cannot happen with a never-cancelled token; kept explicit so a future change
                    // that passes a real token has to decide what to do rather than inherit silence.
                    return;
                }
            }

            lastClientVersion.put(uri, incoming);
            if (repainted != null) {
                latestResultId.remove(uri);
            }
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        replaceTree(uri, null);
        lastClientVersion.remove(uri);
        latestResultId.remove(uri);
        documents.close(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Saving is US6; nothing to do while the mirror is authoritative only for parsing.
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
        String uri = params.getTextDocument().getUri();
        ParsedTree tree = trees.get(uri);
        if (tree == null) {
            // A range request for a document that was never opened is a client bug, but answering
            // with an empty result keeps one confused request from taking the session down.
            return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
        }
        return semanticTokens.range(documents.current(uri).text(), tree, params);
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        // computeAsync rather than supplyAsync: it hands the work a CancelChecker wired to
        // $/cancelRequest. With supplyAsync the future completes as cancelled while the worker keeps
        // walking the tree, which occupies a pool thread exactly when the next keystroke needs it.
        return CompletableFutures.computeAsync(
                checker -> fullTokens(params.getTextDocument().getUri(), LspCancellation.of(checker)));
    }

    @Override
    public CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>> semanticTokensFullDelta(
            SemanticTokensDeltaParams params) {
        return CompletableFutures.computeAsync(
                checker -> {
                    CancellationToken cancellation = LspCancellation.of(checker);
                    String uri = params.getTextDocument().getUri();
                    int[] baseline = sentResults.get(params.getPreviousResultId());

                    // The cache is bounded, so a client can legitimately quote an id that has been
                    // evicted. A full result is the specified recovery; erroring would strand the
                    // client with no route back to a correct highlight.
                    if (baseline == null) {
                        return Either.forLeft(fullTokens(uri, cancellation));
                    }

                    SemanticTokens full = fullTokens(uri, cancellation);
                    int[] current = toArray(full.getData());
                    TokenDelta delta = TokenDiffer.diff(baseline, current, full.getResultId());

                    List<SemanticTokensEdit> edits = new ArrayList<>(delta.edits().size());
                    for (TokenDelta.Edit edit : delta.edits()) {
                        edits.add(
                                new SemanticTokensEdit(edit.start(), edit.deleteCount(), toList(edit.data())));
                    }
                    SemanticTokensDelta result = new SemanticTokensDelta(edits);
                    result.setResultId(full.getResultId());
                    return Either.forRight(result);
                });
    }

    private SemanticTokens fullTokens(String uri, CancellationToken cancellation) {
        ParsedTree tree = trees.get(uri);
        if (tree == null) {
            return new SemanticTokens(UUID.randomUUID().toString(), List.of());
        }

        String text = documents.current(uri).text();
        List<Token> tokens;
        try (SyntaxCursor cursor = tree.cursor()) {
            tokens = new Highlighter().highlight(cursor, new ChangedRange(0, text.length()), cancellation);
        } catch (CancelledException cancelled) {
            // lsp4j reports a cancelled request from CancellationException; translating here keeps
            // the core's exception type out of the protocol layer.
            throw new java.util.concurrent.CancellationException("semantic tokens cancelled");
        }

        int[] encoded =
                SemanticTokenEncoder.encode(text, tokens, VegaLanguageServer.TokenLegend.TYPES);
        String resultId = UUID.randomUUID().toString();
        sentResults.put(resultId, encoded);
        latestResultId.put(uri, resultId);
        return new SemanticTokens(resultId, toList(encoded));
    }

    private Edit toEdit(Document current, TextDocumentContentChangeEvent event) {
        if (event.getRange() == null) {
            // A change with no range replaces the whole document. Permitted by the protocol, and
            // worth handling rather than rejecting: some clients fall back to it after an error.
            return Edit.replace(0, current.content().length(), event.getText(), current.version());
        }
        // content(), not text(): this runs per keystroke, and flattening the document here would be
        // the copy the piece-table representation exists to avoid.
        int[] offsets = LspPositions.offsetsOf(current.content(), event.getRange());
        return Edit.replace(offsets[0], offsets[1], event.getText(), current.version());
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    private static int[] toArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    ParsedTree treeFor(String uri) {
        return trees.get(uri);
    }

    private void replaceTree(String uri, ParsedTree tree) {
        ParsedTree previous = tree == null ? trees.remove(uri) : trees.put(uri, tree);
        if (previous != null && previous != tree) {
            previous.close();
        }
    }
}
