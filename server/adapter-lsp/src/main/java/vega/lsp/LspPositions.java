package vega.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Converts LSP line/character positions to document offsets.
 *
 * <p>Takes a {@link CharSequence} rather than a {@code String} so the caller can pass the document's
 * unflattened text. Requiring a String here would force the whole document to be materialised on
 * every {@code didChange} — reintroducing, one layer up, the per-keystroke copy that the piece-table
 * representation exists to remove.
 *
 * <p>LSP positions are UTF-16 code units, negotiated explicitly in {@code initialize}. Java
 * {@code String} indices are also UTF-16 code units, so this is a line-and-column walk with no
 * encoding arithmetic — tree-sitter's byte offsets were already translated back by the syntax
 * adapter, which is the only component that needs to know two units exist.
 */
final class LspPositions {

    private LspPositions() {}

    static int offsetOf(CharSequence text, Position position) {
        int line = 0;
        int offset = 0;
        while (line < position.getLine() && offset < text.length()) {
            if (text.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }

        // A character index past the end of a short line clamps to the line end rather than spilling
        // into the next one; clients legitimately send column numbers beyond the text.
        int remaining = position.getCharacter();
        while (remaining > 0 && offset < text.length() && text.charAt(offset) != '\n') {
            offset++;
            remaining--;
        }
        return offset;
    }

    static int[] offsetsOf(CharSequence text, Range range) {
        int start = offsetOf(text, range.getStart());
        int end = offsetOf(text, range.getEnd());
        return new int[] {start, Math.max(start, end)};
    }
}
