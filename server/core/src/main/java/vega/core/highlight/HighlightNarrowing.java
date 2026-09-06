package vega.core.highlight;

import java.util.List;

/**
 * Bounds the repaint region around the edit that caused it.
 *
 * <p>The syntax layer's reported changed range is a statement about tree structure, not about
 * appearance. Measurement (research D12) showed it degenerates to the whole document on any brace
 * edit, because ancestry genuinely changes even when every visible token stays identical. Repainting
 * from it directly costs a whole-file highlight on the first '{' a developer types — measured at
 * roughly 320 ms against 0.31 ms for a 60-line window.
 *
 * <p>Narrowing to a window around the edit is sound rather than merely cheap: a user cannot type
 * outside the viewport they are looking at, so the edit position is a sufficient proxy for it and no
 * viewport message is needed (research D13).
 */
public final class HighlightNarrowing {

    /**
     * Half-width of the repaint window, in characters.
     *
     * <p>Roughly a screenful either side of the caret at typical line lengths — the 60-line window
     * the 0.31 ms measurement used, with headroom. It is a bound on work, not a correctness
     * parameter: a window that is too small leaves stale colour on screen until the next edit, and
     * one that is too large simply costs more.
     */
    public static final int DEFAULT_RADIUS = 2_000;

    private HighlightNarrowing() {}

    public static ChangedRange narrow(
            List<ChangedRange> reported, int editOffset, int documentLength) {
        return narrow(reported, editOffset, documentLength, DEFAULT_RADIUS);
    }

    /**
     * Intersects what the syntax layer reported with a window around the edit.
     *
     * @return one span covering every reported range that meets the window, or the window itself
     *     when nothing was reported — the text changed regardless, so the typed character still
     *     needs repainting
     */
    public static ChangedRange narrow(
            List<ChangedRange> reported, int editOffset, int documentLength, int radius) {
        ChangedRange window =
                new ChangedRange(
                        Math.max(0, Math.min(editOffset - radius, documentLength)),
                        Math.min(documentLength, Math.max(0, editOffset + radius)));

        int start = Integer.MAX_VALUE;
        int end = Integer.MIN_VALUE;
        for (ChangedRange range : reported) {
            ChangedRange clipped = range.intersect(window);
            if (!clipped.isEmpty()) {
                start = Math.min(start, clipped.start());
                end = Math.max(end, clipped.end());
            }
        }

        // One span rather than several: the gaps between nearby reported ranges are smaller than the
        // cost of carrying multiple regions through tokenizing, diffing and the protocol.
        return end > start ? new ChangedRange(start, end) : window;
    }
}
