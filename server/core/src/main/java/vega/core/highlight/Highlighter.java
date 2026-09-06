package vega.core.highlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vega.core.port.CancellationToken;
import vega.core.port.SyntaxCursor;

/**
 * Classifies syntax nodes into styled tokens.
 *
 * <p>Walks depth-first through a lazy cursor and emits only leaf nodes that fall inside the
 * requested window. The window is what keeps re-highlighting proportional to the edit: measurement
 * put a whole-file query at roughly 320 ms against 0.31 ms for a 60-line window, so narrowing is
 * the difference between meeting SC-004 and missing it by two orders of magnitude.
 */
public final class Highlighter {

    /** Java keywords appear as anonymous nodes whose type is the keyword text itself. */
    private static final Set<String> KEYWORDS =
            Set.of(
                    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                    "finally", "float", "for", "if", "implements", "import", "instanceof", "int",
                    "interface", "long", "native", "new", "package", "private", "protected", "public",
                    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
                    "throw", "throws", "transient", "try", "void", "volatile", "while", "record",
                    "sealed", "permits", "yield", "var");

    /** Nodes walked between cancellation checks. See {@link #highlight}. */
    private static final int CANCELLATION_CHECK_STRIDE = 64;

    private static final Map<String, String> NODE_TYPE_TO_TOKEN_TYPE =
            Map.ofEntries(
                    Map.entry("type_identifier", "type"),
                    Map.entry("identifier", "variable"),
                    Map.entry("field_identifier", "variable"),
                    Map.entry("method_invocation", "function"),
                    Map.entry("string_literal", "string"),
                    Map.entry("character_literal", "string"),
                    Map.entry("text_block", "string"),
                    Map.entry("decimal_integer_literal", "number"),
                    Map.entry("hex_integer_literal", "number"),
                    Map.entry("decimal_floating_point_literal", "number"),
                    Map.entry("line_comment", "comment"),
                    Map.entry("block_comment", "comment"));

    public List<Token> highlight(SyntaxCursor cursor, ChangedRange window) {
        return highlight(cursor, window, CancellationToken.never());
    }

    /**
     * Walks the tree, checking for cancellation as it goes.
     *
     * <p>The check is periodic rather than per-node: reading the token on every node measurably
     * slows the walk that the window narrowing exists to keep cheap, while a stride of
     * {@value #CANCELLATION_CHECK_STRIDE} nodes bounds the overshoot to far less than a frame.
     */
    public List<Token> highlight(SyntaxCursor cursor, ChangedRange window, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        List<Token> tokens = new ArrayList<>();
        collect(cursor, window, tokens, cancellation, new int[1]);
        tokens.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return tokens;
    }

    private void collect(
            SyntaxCursor cursor,
            ChangedRange window,
            List<Token> tokens,
            CancellationToken cancellation,
            int[] visited) {
        boolean descended = cursor.gotoFirstChild();
        if (!descended) {
            emitIfClassified(cursor, window, tokens);
            return;
        }

        do {
            if (++visited[0] % CANCELLATION_CHECK_STRIDE == 0) {
                cancellation.throwIfCancelled();
            }
            // Children are in document order, so once one starts past the window every later
            // sibling does too. Without this the walk visits the entire tree and the "narrowing"
            // narrows only what is emitted, not what is traversed — measured at 33 ms per window
            // before this exit, which is the whole SC-004 budget spent on skipping nodes.
            if (cursor.startOffset() >= window.end()) {
                break;
            }
            if (cursor.endOffset() > window.start()) {
                collect(cursor, window, tokens, cancellation, visited);
            }
        } while (cursor.gotoNextSibling());

        cursor.gotoParent();
    }

    private void emitIfClassified(SyntaxCursor cursor, ChangedRange window, List<Token> tokens) {
        int start = cursor.startOffset();
        int end = cursor.endOffset();

        // Error recovery inserts MISSING nodes with zero width; there is nothing to paint.
        if (end <= start) {
            return;
        }
        if (end <= window.start() || start >= window.end()) {
            return;
        }

        String nodeType = cursor.nodeType();
        String tokenType =
                KEYWORDS.contains(nodeType) ? "keyword" : NODE_TYPE_TO_TOKEN_TYPE.get(nodeType);
        if (tokenType == null) {
            return;
        }
        tokens.add(new Token(start, end - start, tokenType));
    }
}
