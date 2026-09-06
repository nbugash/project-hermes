package vega.core.port;

import vega.core.document.Edit;

/** Incremental, error-tolerant parsing. Implemented by an adapter; never by the core. */
public interface SyntaxParserPort {

    ParsedTree parse(CharSequence text, CancellationToken cancellation);

    /**
     * Reparses after an edit, reusing the previous tree.
     *
     * <p>Implementations must feed the source incrementally rather than converting the whole
     * document per call: measurement showed the whole-copy path costs O(file size) per keystroke and
     * misses the budget by roughly fivefold.
     *
     * <p><strong>{@code previous} is consumed, not merely read.</strong> Reuse requires shifting the
     * old tree's node positions to account for the edit, which happens in place, so after this call
     * {@code previous} describes neither the old text nor the new one. Anything needed from it — a
     * token snapshot, a range comparison — must be taken beforehand. Reading it afterwards does not
     * fail; it silently returns positions offset by the edit, which surfaces much later as
     * highlighting that is wrong by exactly the number of characters typed.
     */
    ParsedTree reparse(
            ParsedTree previous, Edit edit, CharSequence newText, CancellationToken cancellation);
}
