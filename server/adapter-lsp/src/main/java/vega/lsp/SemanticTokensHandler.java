package vega.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import vega.core.highlight.ChangedRange;
import vega.core.highlight.Highlighter;
import vega.core.highlight.SemanticTokenEncoder;
import vega.core.highlight.Token;
import vega.core.obs.Correlation;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;

/**
 * Serves {@code textDocument/semanticTokens/range}.
 *
 * <p>Range-first is what keeps whole-file tokenization off the cold-start path: the editor asks for
 * the viewport it is about to paint, and measurement put a whole-file query at roughly 320 ms
 * against 0.31 ms for a 60-line window (research D3, D13). Answering a range request by tokenizing
 * the document and slicing afterwards would return identical bytes and miss the entire point.
 *
 * <p>Positions on the wire are UTF-16 code units, which the server negotiated in {@code initialize}.
 * Java {@code String} indices are also UTF-16 code units, so the conversion here is a line-and-column
 * walk with no encoding arithmetic — the byte offsets tree-sitter uses were already translated back
 * by the syntax adapter.
 */
public final class SemanticTokensHandler {

    private final List<String> legend;

    public SemanticTokensHandler(List<String> legend) {
        this.legend = legend;
    }

    public CompletableFuture<SemanticTokens> range(
            String text, ParsedTree tree, SemanticTokensRangeParams params) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (var scope = Correlation.beginScope(Correlation.newId())) {
                        ChangedRange window = toOffsetRange(text, params.getRange());

                        List<Token> tokens;
                        try (SyntaxCursor cursor = tree.cursor()) {
                            tokens = new Highlighter().highlight(cursor, window);
                        }

                        int[] encoded = SemanticTokenEncoder.encode(text, tokens, legend);
                        List<Integer> data = new ArrayList<>(encoded.length);
                        for (int value : encoded) {
                            data.add(value);
                        }
                        return new SemanticTokens(data);
                    }
                });
    }

    private static ChangedRange toOffsetRange(String text, Range range) {
        int[] offsets = LspPositions.offsetsOf(text, range);
        return new ChangedRange(offsets[0], offsets[1]);
    }
}
