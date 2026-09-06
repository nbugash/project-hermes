package vega.core.document;

import java.util.Arrays;

/**
 * Byte offsets at which each line starts, maintained incrementally.
 *
 * <p>Converting a byte offset to a row/column pair by scanning from the start of the document is
 * O(file size) per edit — measured at 1.1 ms on a 1.4 MB file, a fifth of the whole re-analysis
 * budget spent on counting newlines. Principle VI's "incremental everything" applies here too: the
 * index is built once and then shifted, never recomputed.
 */
public final class LineIndex {

    private int[] lineStarts;

    private LineIndex(int[] lineStarts) {
        this.lineStarts = lineStarts;
    }

    public static LineIndex of(byte[] source) {
        return of(source, source.length);
    }

    /** Indexes only the first {@code length} bytes, for callers whose array has spare capacity. */
    public static LineIndex of(byte[] source, int length) {
        int[] starts = new int[Math.max(16, length / 32)];
        int count = 0;
        starts[count++] = 0;
        for (int i = 0; i < length; i++) {
            if (source[i] == '\n') {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, starts.length * 2);
                }
                starts[count++] = i + 1;
            }
        }
        return new LineIndex(Arrays.copyOf(starts, count));
    }

    public int rowAt(int byteOffset) {
        int index = Arrays.binarySearch(lineStarts, byteOffset);
        return index >= 0 ? index : -index - 2;
    }

    public int columnAt(int byteOffset) {
        return byteOffset - lineStarts[rowAt(byteOffset)];
    }

    /**
     * Applies an edit by shifting the line starts after it, inserting or removing entries for any
     * newlines the edit added or deleted.
     *
     * <p>Shifting touches only the tail, so a keystroke costs a scan of the remaining line starts
     * rather than of the whole document.
     */
    public LineIndex edited(int startByte, int oldEndByte, byte[] newSource, int newEndByte) {
        int firstAffected = rowAt(startByte) + 1;
        int delta = newEndByte - oldEndByte;

        int newlinesInReplacement = 0;
        for (int i = startByte; i < newEndByte; i++) {
            if (newSource[i] == '\n') {
                newlinesInReplacement++;
            }
        }

        int removed = 0;
        while (firstAffected + removed < lineStarts.length && lineStarts[firstAffected + removed] <= oldEndByte) {
            removed++;
        }

        int[] updated = new int[lineStarts.length - removed + newlinesInReplacement];
        System.arraycopy(lineStarts, 0, updated, 0, firstAffected);

        int out = firstAffected;
        for (int i = startByte; i < newEndByte; i++) {
            if (newSource[i] == '\n') {
                updated[out++] = i + 1;
            }
        }
        for (int i = firstAffected + removed; i < lineStarts.length; i++) {
            updated[out++] = lineStarts[i] + delta;
        }

        return new LineIndex(Arrays.copyOf(updated, out));
    }

    public int lineCount() {
        return lineStarts.length;
    }
}
