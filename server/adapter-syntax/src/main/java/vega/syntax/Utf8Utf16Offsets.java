package vega.syntax;

import java.util.Arrays;

/**
 * Translates between tree-sitter's UTF-8 byte offsets and the UTF-16 code-unit offsets used by the
 * core and by LSP.
 *
 * <p>Java {@code String} indices are already UTF-16 code units, so the core's {@code Document} and
 * the protocol agree with each other for free; the only unit that differs is tree-sitter's. That
 * makes this class the whole of the reconciliation, and the adapter the only place that has to know
 * two units exist.
 *
 * <p>Source code is overwhelmingly ASCII, so the common case is detected once and translated as
 * identity with no table and no search. Where a document does contain non-ASCII, one entry is stored
 * per multi-byte character; a Java file with an accented comment costs a handful of entries, and
 * only a file written entirely in non-Latin script approaches two ints per character.
 */
final class Utf8Utf16Offsets {

    private static final Utf8Utf16Offsets ASCII = new Utf8Utf16Offsets(null, null, null, null, 0);

    /** Byte offset at which each multi-byte character starts. Sorted, so binary search applies. */
    private final int[] startByte;

    /** UTF-16 offset at which that same character starts. Sorted in lockstep with startByte. */
    private final int[] startUtf16;

    private final byte[] byteLength;
    private final byte[] utf16Length;
    private final int count;

    private Utf8Utf16Offsets(
            int[] startByte, int[] startUtf16, byte[] byteLength, byte[] utf16Length, int count) {
        this.startByte = startByte;
        this.startUtf16 = startUtf16;
        this.byteLength = byteLength;
        this.utf16Length = utf16Length;
        this.count = count;
    }

    static Utf8Utf16Offsets of(byte[] source) {
        return of(source, source.length);
    }

    /** Scans only the first {@code length} bytes, for callers whose array has spare capacity. */
    static Utf8Utf16Offsets of(byte[] source, int length) {
        int firstNonAscii = -1;
        for (int i = 0; i < length; i++) {
            if (source[i] < 0) {
                firstNonAscii = i;
                break;
            }
        }
        if (firstNonAscii < 0) {
            return ASCII;
        }

        int[] starts = new int[16];
        int[] utf16s = new int[16];
        byte[] byteLens = new byte[16];
        byte[] utf16Lens = new byte[16];
        int count = 0;

        // Everything before the first non-ASCII byte maps one-to-one, so the scan starts there and
        // the UTF-16 cursor starts equal to it.
        int utf16 = firstNonAscii;
        for (int i = firstNonAscii; i < length; ) {
            int b = source[i] & 0xFF;
            if (b < 0x80) {
                i++;
                utf16++;
                continue;
            }

            int len = b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : 2;
            // Four-byte sequences are a surrogate pair in UTF-16; everything else is one unit.
            int units = len == 4 ? 2 : 1;

            if (count == starts.length) {
                starts = Arrays.copyOf(starts, count * 2);
                utf16s = Arrays.copyOf(utf16s, count * 2);
                byteLens = Arrays.copyOf(byteLens, count * 2);
                utf16Lens = Arrays.copyOf(utf16Lens, count * 2);
            }
            starts[count] = i;
            utf16s[count] = utf16;
            byteLens[count] = (byte) len;
            utf16Lens[count] = (byte) units;
            count++;

            i += len;
            utf16 += units;
        }

        return new Utf8Utf16Offsets(starts, utf16s, byteLens, utf16Lens, count);
    }

    boolean isAscii() {
        return count == 0;
    }

    int toUtf16(int byteOffset) {
        if (count == 0) {
            return byteOffset;
        }
        int i = floorIndex(startByte, byteOffset);
        if (i < 0) {
            return byteOffset;
        }
        int charEndByte = startByte[i] + byteLength[i];
        if (byteOffset >= charEndByte) {
            return startUtf16[i] + utf16Length[i] + (byteOffset - charEndByte);
        }
        // An offset landing inside a multi-byte character is not a position the core can name;
        // report the character's start rather than inventing a split-character index.
        return startUtf16[i];
    }

    int toByte(int utf16Offset) {
        if (count == 0) {
            return utf16Offset;
        }
        int i = floorIndex(startUtf16, utf16Offset);
        if (i < 0) {
            return utf16Offset;
        }
        int charEndUtf16 = startUtf16[i] + utf16Length[i];
        if (utf16Offset >= charEndUtf16) {
            return startByte[i] + byteLength[i] + (utf16Offset - charEndUtf16);
        }
        return startByte[i];
    }

    /** Index of the last entry starting at or before {@code value}, or -1 if there is none. */
    private int floorIndex(int[] sortedStarts, int value) {
        int index = Arrays.binarySearch(sortedStarts, 0, count, value);
        return index >= 0 ? index : -index - 2;
    }
}
