package vega.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The shapes of document that break assumptions.
 *
 * <p>Each of these is a file someone really has: a generated single-line bundle, an empty
 * placeholder, a file being pasted into wholesale. They are cheap to handle and expensive to
 * discover in production, because each one fails somewhere far from its cause.
 */
class EdgeCaseTest {

    private static Document open(String text) {
        return Document.opened("file:///A.java", text);
    }

    @Test
    void anEmptyDocumentAcceptsAnEdit() {
        Document document = open("");

        Document edited = document.apply(Edit.replace(0, 0, "class A {}", document.version()));

        assertEquals("class A {}", edited.text());
    }

    @Test
    void anEmptyDocumentReportsAnEmptyLineIndex() {
        LineIndex index = LineIndex.of(new byte[0]);

        // One line, of length zero — not zero lines. An empty file still has a caret position.
        assertEquals(1, index.lineCount());
        assertEquals(0, index.rowAt(0));
    }

    @Test
    void aSingleLineDocumentOfSubstantialLengthIsHandled() {
        // Generated and minified Java really does produce lines like this, and every line-based
        // assumption in the pipeline meets its worst case here.
        String oneLine = "class A { " + "int x; ".repeat(20_000) + "}";
        Document document = open(oneLine);

        Document edited = document.apply(Edit.replace(10, 10, "long y; ", document.version()));

        assertTrue(edited.text().startsWith("class A { long y; "));
        assertEquals(1, LineIndex.of(oneLine.getBytes(java.nio.charset.StandardCharsets.UTF_8)).lineCount());
    }

    @Test
    void aLargePasteIsASingleEditRatherThanManySmallOnes() {
        Document document = open("class A {\n}\n");
        String pasted = "    int field;\n".repeat(5_000);

        Document edited = document.apply(Edit.replace(10, 10, pasted, document.version()));

        // One version increment for one paste: treating it as thousands of edits would issue
        // thousands of reparses for a single user action.
        assertEquals(document.version() + 1, edited.version());
        assertTrue(edited.text().contains(pasted));
    }

    @Test
    void anEditSpanningTheWholeDocumentIsAccepted() {
        Document document = open("class A {}");

        Document edited =
                document.apply(Edit.replace(0, document.text().length(), "class B {}", document.version()));

        assertEquals("class B {}", edited.text());
    }

    @Test
    void anEditBeyondTheEndOfTheDocumentIsRejected() {
        Document document = open("class A {}");

        // Rejecting is the point: an out-of-range edit means the mirror and the buffer already
        // disagree, and applying it would encode that disagreement into the text.
        assertThrows(
                StringIndexOutOfBoundsException.class,
                () -> document.apply(Edit.replace(500, 600, "x", document.version())));
    }

    @Test
    void aDocumentWithNoTrailingNewlineKeepsThatStateThroughAnEdit() {
        Document document = open("class A {}");

        Document edited = document.apply(Edit.replace(10, 10, " // note", document.version()));

        assertTrue(!edited.text().endsWith("\n"), "an edit must not invent a trailing newline");
    }

    @Test
    void deletingEverythingLeavesAValidEmptyDocument() {
        Document document = open("class A {}");

        Document edited =
                document.apply(Edit.replace(0, document.text().length(), "", document.version()));

        assertEquals("", edited.text());
        assertEquals(document.version() + 1, edited.version());
    }

    @Test
    void lineIndexHandlesADocumentOfOnlyNewlines() {
        LineIndex index = LineIndex.of("\n\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(4, index.lineCount());
        assertEquals(3, index.rowAt(3));
    }
}
