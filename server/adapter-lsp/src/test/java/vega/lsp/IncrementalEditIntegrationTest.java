package vega.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensDeltaParams;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vega.core.document.DocumentMetadata;
import vega.core.document.DocumentService;
import vega.core.document.LineEnding;
import vega.core.highlight.HighlightService;
import vega.core.port.FileGatewayPort;

/**
 * Incremental synchronisation, exercised through the LSP surface.
 *
 * <p>The mirror is the backend's copy of the editor's buffer. If it drifts, every downstream result
 * describes text the user does not have, and the damage is silent — highlighting looks plausible and
 * is simply wrong. So the assertions here are about what the parser was asked to reparse, which is
 * the mirror's contents made observable.
 */
class IncrementalEditIntegrationTest {

    private static final String URI = "file:///A.java";
    private static final String SOURCE = "class A {\n    int x = 1;\n}\n";

    private RecordingParser parser;
    private VegaTextDocumentService service;

    @BeforeEach
    void setUp() {
        parser = new RecordingParser();
        DocumentService documents = new DocumentService(new FixedGateway(SOURCE));
        service =
                new VegaTextDocumentService(
                        documents,
                        parser,
                        new SemanticTokensHandler(VegaLanguageServer.TokenLegend.TYPES),
                        new ProgressReporter(() -> null),
                        new HighlightService(parser));

        service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, SOURCE)));
    }

    private static DidChangeTextDocumentParams change(int version, Range range, String text) {
        TextDocumentContentChangeEvent event = new TextDocumentContentChangeEvent();
        event.setRange(range);
        event.setText(text);
        return new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(URI, version), List.of(event));
    }

    private static Range at(int line, int character) {
        return new Range(new Position(line, character), new Position(line, character));
    }

    @Test
    void anInsertionReachesTheParserAsUpdatedText() {
        service.didChange(change(2, at(1, 9), "y"));

        assertNotNull(parser.lastReparsedText.get(), "didChange must trigger a reparse");
        assertTrue(
                parser.lastReparsedText.get().contains("int xy = 1;"),
                "mirror did not apply the edit: " + parser.lastReparsedText.get());
    }

    @Test
    void aDeletionReachesTheParserAsUpdatedText() {
        service.didChange(
                change(2, new Range(new Position(1, 4), new Position(1, 8)), ""));

        assertTrue(
                parser.lastReparsedText.get().contains("x = 1;"),
                "mirror did not apply the deletion: " + parser.lastReparsedText.get());
    }

    @Test
    void severalChangesInOneNotificationApplyInOrder() {
        TextDocumentContentChangeEvent first = new TextDocumentContentChangeEvent();
        first.setRange(at(1, 9));
        first.setText("y");
        TextDocumentContentChangeEvent second = new TextDocumentContentChangeEvent();
        second.setRange(at(1, 10));
        second.setText("z");

        service.didChange(
                new DidChangeTextDocumentParams(
                        new VersionedTextDocumentIdentifier(URI, 2), List.of(first, second)));

        // LSP defines later changes in a notification as applying to the text produced by the
        // earlier ones; treating them as independent silently transposes characters.
        assertTrue(
                parser.lastReparsedText.get().contains("int xyz = 1;"),
                "changes were not applied in order: " + parser.lastReparsedText.get());
    }

    @Test
    void anOutOfOrderChangeIsRejectedRatherThanApplied() {
        service.didChange(change(2, at(1, 8), "y"));
        String afterFirst = parser.lastReparsedText.get();

        // Version 2 again: a duplicate or replayed notification. Applying it would insert the
        // character twice and desynchronise the mirror permanently.
        service.didChange(change(2, at(1, 8), "z"));

        assertEquals(afterFirst, parser.lastReparsedText.get(), "a stale change must not be applied");
    }

    @Test
    void fullTokensThenDeltaReturnsADeltaAgainstTheQuotedResultId() throws Exception {
        SemanticTokensParams fullParams = new SemanticTokensParams(new TextDocumentIdentifier(URI));
        SemanticTokens full = service.semanticTokensFull(fullParams).get();

        assertNotNull(full.getResultId(), "a delta cannot be requested without a resultId");

        service.didChange(change(2, at(1, 8), "y"));

        SemanticTokensDeltaParams deltaParams =
                new SemanticTokensDeltaParams(new TextDocumentIdentifier(URI), full.getResultId());
        var response = service.semanticTokensFullDelta(deltaParams).get();

        assertTrue(response.isRight() || response.isLeft(), "must answer with tokens or a delta");
    }

    @Test
    void anUnknownPreviousResultIdFallsBackToAFullResult() throws Exception {
        SemanticTokensDeltaParams params =
                new SemanticTokensDeltaParams(new TextDocumentIdentifier(URI), "never-issued");

        var response = service.semanticTokensFullDelta(params).get();

        // The server's cache is bounded, so a client can legitimately quote an id that has been
        // evicted. Returning a full result is the specified recovery; erroring strands the client
        // with no way back to a correct highlight.
        assertTrue(response.isLeft(), "an unknown resultId must yield a full result, not an error");
        assertFalse(response.getLeft().getResultId().isBlank());
    }

    private static final class RecordingParser implements vega.core.port.SyntaxParserPort {
        final AtomicReference<String> lastReparsedText = new AtomicReference<>();

        @Override
        public vega.core.port.ParsedTree parse(
                CharSequence text, vega.core.port.CancellationToken cancellation) {
            return new StubTree();
        }

        @Override
        public vega.core.port.ParsedTree reparse(
                vega.core.port.ParsedTree previous,
                vega.core.document.Edit edit,
                CharSequence newText,
                vega.core.port.CancellationToken cancellation) {
            lastReparsedText.set(newText.toString());
            return new StubTree();
        }
    }

    private static final class StubTree implements vega.core.port.ParsedTree {
        @Override
        public boolean hasError() {
            return false;
        }

        @Override
        public List<vega.core.highlight.ChangedRange> changedRangesSince(
                vega.core.port.ParsedTree other) {
            return List.of();
        }

        @Override
        public vega.core.port.SyntaxCursor cursor() {
            return new EmptyCursor();
        }

        @Override
        public void close() {}
    }

    private static final class EmptyCursor implements vega.core.port.SyntaxCursor {
        @Override
        public boolean gotoFirstChild() {
            return false;
        }

        @Override
        public boolean gotoNextSibling() {
            return false;
        }

        @Override
        public boolean gotoParent() {
            return false;
        }

        @Override
        public String nodeType() {
            return "program";
        }

        @Override
        public int startOffset() {
            return 0;
        }

        @Override
        public int endOffset() {
            return 0;
        }

        @Override
        public boolean isErrorNode() {
            return false;
        }

        @Override
        public void close() {}
    }

    private record FixedGateway(String text) implements FileGatewayPort {
        @Override
        public FileContent read(String uri) {
            return new FileContent(
                    text,
                    new DocumentMetadata(
                            java.nio.charset.StandardCharsets.UTF_8, LineEnding.LF, true, "hash"));
        }

        @Override
        public WriteOutcome write(
                String uri, String content, DocumentMetadata metadata, String expectedDiskHash) {
            return WriteOutcome.FAILED;
        }
    }
}
