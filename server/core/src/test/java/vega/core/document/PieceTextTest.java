package vega.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Text that can be edited without copying the whole document.
 *
 * <p>Every operation here has an obvious correct answer that a plain {@code String} already gives.
 * The point is to get those answers without rebuilding the document per keystroke, so the tests are
 * written as equivalence against the obvious implementation — if a piece-table edit and a
 * substring-and-concat ever disagree, the piece table is wrong.
 */
class PieceTextTest {

    private static String naive(String text, int start, int end, String replacement) {
        return text.substring(0, start) + replacement + text.substring(end);
    }

    @Test
    void reportsLengthAndContentOfAPlainString() {
        PieceText text = PieceText.of("class A {}");

        assertEquals(10, text.length());
        assertEquals("class A {}", text.toString());
        assertEquals('c', text.charAt(0));
        assertEquals('}', text.charAt(9));
    }

    @Test
    void insertionMatchesSubstringAndConcat() {
        String original = "class A {\n    int x;\n}\n";
        PieceText text = PieceText.of(original).replace(9, 9, " // note");

        assertEquals(naive(original, 9, 9, " // note"), text.toString());
    }

    @Test
    void deletionMatchesSubstringAndConcat() {
        String original = "class A {\n    int x;\n}\n";
        PieceText text = PieceText.of(original).replace(10, 20, "");

        assertEquals(naive(original, 10, 20, ""), text.toString());
    }

    @Test
    void replacementMatchesSubstringAndConcat() {
        String original = "class A {}";
        PieceText text = PieceText.of(original).replace(6, 7, "Renamed");

        assertEquals(naive(original, 6, 7, "Renamed"), text.toString());
    }

    @Test
    void manySequentialEditsMatchTheNaiveResult() {
        String expected = "class A {}";
        PieceText text = PieceText.of(expected);

        for (int i = 0; i < 200; i++) {
            int at = 9;
            expected = naive(expected, at, at, "x");
            text = text.replace(at, at, "x");
        }

        assertEquals(expected, text.toString());
        assertEquals(expected.length(), text.length());
    }

    @Test
    void charAtIsCorrectAcrossPieceBoundaries() {
        PieceText text = PieceText.of("abcdef").replace(3, 3, "XYZ");
        String expected = "abcXYZdef";

        for (int i = 0; i < expected.length(); i++) {
            assertEquals(expected.charAt(i), text.charAt(i), "index " + i);
        }
    }

    @Test
    void editingLeavesTheOriginalUntouched() {
        PieceText original = PieceText.of("class A {}");

        PieceText edited = original.replace(9, 9, " int x;");

        // Immutability is load-bearing: an in-flight highlight finishes against the version it
        // started on precisely because that version cannot change underneath it.
        assertEquals("class A {}", original.toString());
        assertEquals("class A { int x;}", edited.toString());
    }

    @Test
    void materialisedTextIsCachedRatherThanRebuiltPerCall() {
        PieceText text = PieceText.of("class A {}").replace(9, 9, "x");

        assertSame(text.toString(), text.toString());
    }

    @Test
    void compactsSoLookupCostStaysBounded() {
        PieceText text = PieceText.of("start");
        for (int i = 0; i < 500; i++) {
            text = text.replace(text.length(), text.length(), "y");
        }

        // Without compaction the piece count grows with the edit count and every charAt walks all of
        // them — an editing session would get progressively slower for no visible reason.
        assertTrue(text.pieceCount() <= 64, "piece count grew unbounded: " + text.pieceCount());
        assertEquals(505, text.length());
    }

    @Test
    void handlesAnEmptyDocument() {
        PieceText text = PieceText.of("");

        assertEquals(0, text.length());
        assertEquals("inserted", text.replace(0, 0, "inserted").toString());
    }

    @Test
    void handlesDeletingEverything() {
        PieceText text = PieceText.of("class A {}").replace(0, 10, "");

        assertEquals("", text.toString());
        assertEquals(0, text.length());
    }
}
