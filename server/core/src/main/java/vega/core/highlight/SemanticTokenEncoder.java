package vega.core.highlight;

import java.util.List;

/**
 * Encodes tokens into LSP's relative five-integer form.
 *
 * <p>Each token is {@code deltaLine, deltaStart, length, tokenType, tokenModifiers}, where the
 * deltas are measured against the previous token — {@code deltaStart} restarts from the line origin
 * whenever {@code deltaLine} is non-zero.
 *
 * <p>The relativity is deliberate on LSP's part (most tokens stay stable relative to each other
 * across edits, which is what makes deltas small) and is also why an arithmetic slip here is not a
 * local defect: every subsequent token in the document shifts.
 */
public final class SemanticTokenEncoder {

    private static final int FIELDS_PER_TOKEN = 5;

    private SemanticTokenEncoder() {}

    public static int[] encode(String text, List<Token> tokens, List<String> legend) {
        int[] encoded = new int[tokens.size() * FIELDS_PER_TOKEN];
        int out = 0;

        int previousLine = 0;
        int previousColumn = 0;
        int scanned = 0;
        int line = 0;
        int lineStart = 0;

        for (Token token : tokens) {
            int typeIndex = legend.indexOf(token.type());
            if (typeIndex < 0) {
                // Unknown types are dropped rather than guessed: an index outside the legend would
                // be read by the client as some other type entirely.
                continue;
            }

            // Advance the line counter to this token, carrying position forward so the whole scan
            // stays linear in the text rather than quadratic in the token count.
            while (scanned < token.start()) {
                if (text.charAt(scanned) == '\n') {
                    line++;
                    lineStart = scanned + 1;
                }
                scanned++;
            }
            int column = token.start() - lineStart;

            int deltaLine = line - previousLine;
            int deltaStart = deltaLine == 0 ? column - previousColumn : column;

            encoded[out++] = deltaLine;
            encoded[out++] = deltaStart;
            encoded[out++] = token.length();
            encoded[out++] = typeIndex;
            encoded[out++] = 0;

            previousLine = line;
            previousColumn = column;
        }

        if (out == encoded.length) {
            return encoded;
        }
        int[] trimmed = new int[out];
        System.arraycopy(encoded, 0, trimmed, 0, out);
        return trimmed;
    }
}
