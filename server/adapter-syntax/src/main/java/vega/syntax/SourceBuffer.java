package vega.syntax;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The document's UTF-8 bytes, edited in place.
 *
 * <p>Splicing an edit by allocating a fresh array copied 1.8 MB per keystroke on the measured corpus
 * and produced the garbage behind a 25 ms p99.9 tail. Growth here is amortised instead: the tail is
 * shifted with {@code System.arraycopy} inside one array, and a new array is allocated only when the
 * document outgrows its capacity.
 *
 * <p><strong>Only the most recent tree's view of this buffer is valid.</strong> A reparse mutates it,
 * so a {@link TreeSitterParsedTree} created before that edit describes bytes that no longer exist.
 * That is safe because the buffer is read in exactly one place — the start of the next reparse,
 * before any mutation — and the class is package-private so nothing outside the adapter can hold it
 * across an edit. It is the reason this optimisation was deferred once (ADR-0003): the invariant is
 * cheap to state and easy to break, so it is stated here rather than left to be inferred.
 */
final class SourceBuffer {

    private byte[] bytes;
    private int length;

    private SourceBuffer(byte[] bytes, int length) {
        this.bytes = bytes;
        this.length = length;
    }

    static SourceBuffer of(byte[] initial) {
        // Headroom so the first edits do not immediately reallocate. Typing grows a document slowly,
        // so a small margin absorbs a long session.
        byte[] storage = Arrays.copyOf(initial, initial.length + Math.max(1024, initial.length / 16));
        return new SourceBuffer(storage, initial.length);
    }

    int length() {
        return length;
    }

    /**
     * The backing array. Valid only up to {@link #length()}; everything past that is stale capacity.
     */
    byte[] array() {
        return bytes;
    }

    /** Replaces {@code [start, end)} with {@code replacement}, growing only if capacity demands it. */
    void replace(int start, int end, byte[] replacement) {
        int clampedStart = Math.min(start, length);
        int clampedEnd = Math.min(Math.max(end, clampedStart), length);
        int newLength = length - (clampedEnd - clampedStart) + replacement.length;

        if (newLength > bytes.length) {
            // Doubling rather than exact-fitting: a document being typed into grows one character at
            // a time, and exact growth would reallocate on every keystroke — the cost this exists to
            // remove.
            bytes = Arrays.copyOf(bytes, Math.max(newLength, bytes.length * 2));
        }

        int tailLength = length - clampedEnd;
        int tailDestination = clampedStart + replacement.length;
        if (tailLength > 0 && tailDestination != clampedEnd) {
            System.arraycopy(bytes, clampedEnd, bytes, tailDestination, tailLength);
        }
        System.arraycopy(replacement, 0, bytes, clampedStart, replacement.length);
        length = newLength;
    }

    /** Decodes a range as UTF-8. Used by the chunked reader that feeds tree-sitter. */
    String decode(int from, int to) {
        return new String(bytes, from, to - from, StandardCharsets.UTF_8);
    }

    byte byteAt(int index) {
        return bytes[index];
    }
}
