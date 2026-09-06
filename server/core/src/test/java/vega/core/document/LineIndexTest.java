package vega.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Line arithmetic is correctness-critical: an off-by-one here misplaces the points handed to the
 * parser, and every token position downstream inherits the error.
 */
class LineIndexTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void firstLineStartsAtRowZeroColumnZero() {
        LineIndex index = LineIndex.of(bytes("abc\ndef\n"));

        assertEquals(0, index.rowAt(0));
        assertEquals(0, index.columnAt(0));
    }

    @Test
    void reportsColumnWithinTheFirstLine() {
        LineIndex index = LineIndex.of(bytes("abc\ndef\n"));

        assertEquals(0, index.rowAt(2));
        assertEquals(2, index.columnAt(2));
    }

    @Test
    void offsetImmediatelyAfterANewlineIsColumnZeroOfTheNextRow() {
        LineIndex index = LineIndex.of(bytes("abc\ndef\n"));

        assertEquals(1, index.rowAt(4));
        assertEquals(0, index.columnAt(4));
    }

    @Test
    void theNewlineItselfBelongsToTheRowItTerminates() {
        LineIndex index = LineIndex.of(bytes("abc\ndef\n"));

        assertEquals(0, index.rowAt(3));
        assertEquals(3, index.columnAt(3));
    }

    @Test
    void countsEveryLineIncludingTheEmptyOneAfterATrailingNewline() {
        assertEquals(3, LineIndex.of(bytes("a\nb\n")).lineCount());
        assertEquals(2, LineIndex.of(bytes("a\nb")).lineCount());
        assertEquals(1, LineIndex.of(bytes("a")).lineCount());
    }

    @Test
    void shiftsLaterLinesWhenTextIsInsertedWithoutNewlines() {
        byte[] before = bytes("abc\ndef\n");
        byte[] after = bytes("abXYc\ndef\n");

        LineIndex updated = LineIndex.of(before).edited(2, 2, after, 4);

        assertEquals(1, updated.rowAt(6));
        assertEquals(0, updated.columnAt(6));
        assertEquals(3, updated.lineCount());
    }

    @Test
    void addsARowWhenTheInsertionContainsANewline() {
        byte[] before = bytes("abc\ndef\n");
        byte[] after = bytes("ab\nc\ndef\n");

        LineIndex updated = LineIndex.of(before).edited(2, 2, after, 3);

        assertEquals(4, updated.lineCount());
        assertEquals(1, updated.rowAt(3));
        assertEquals(2, updated.rowAt(5));
    }

    @Test
    void removesARowWhenANewlineIsDeleted() {
        byte[] before = bytes("abc\ndef\n");
        byte[] after = bytes("abcdef\n");

        LineIndex updated = LineIndex.of(before).edited(3, 4, after, 3);

        assertEquals(2, updated.lineCount());
        assertEquals(0, updated.rowAt(5));
        assertEquals(5, updated.columnAt(5));
    }

    @Test
    void matchesAFullRebuildAfterAnEdit() {
        // The incremental path must be indistinguishable from recomputation; that equivalence is
        // the whole justification for maintaining the index rather than rebuilding it.
        byte[] before = bytes("one\ntwo\nthree\nfour\n");
        byte[] after = bytes("one\ntwALTEREDo\nthree\nfour\n");

        LineIndex incremental = LineIndex.of(before).edited(6, 6, after, 13);
        LineIndex rebuilt = LineIndex.of(after);

        assertEquals(rebuilt.lineCount(), incremental.lineCount());
        for (int offset = 0; offset < after.length; offset++) {
            assertEquals(rebuilt.rowAt(offset), incremental.rowAt(offset), "row at " + offset);
            assertEquals(rebuilt.columnAt(offset), incremental.columnAt(offset), "column at " + offset);
        }
    }
}
