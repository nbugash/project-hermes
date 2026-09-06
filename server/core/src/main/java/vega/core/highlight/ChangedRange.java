package vega.core.highlight;

/**
 * A span the syntax layer reports as structurally affected by an edit.
 *
 * <p>Deliberately NOT the re-highlight region. Measurement showed the reported range degenerates to
 * the whole document on any brace open or close, because node ancestry genuinely changes even when
 * every token's appearance is identical. Driving a repaint from this directly would repaint the
 * entire file the first time a developer types '{'. See research D12.
 */
public record ChangedRange(int start, int end) {

    public ChangedRange {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid range: " + start + ".." + end);
        }
    }

    public int length() {
        return end - start;
    }

    public ChangedRange intersect(ChangedRange other) {
        int lo = Math.max(start, other.start);
        int hi = Math.min(end, other.end);
        return lo >= hi ? new ChangedRange(lo, lo) : new ChangedRange(lo, hi);
    }

    public boolean isEmpty() {
        return start == end;
    }
}
