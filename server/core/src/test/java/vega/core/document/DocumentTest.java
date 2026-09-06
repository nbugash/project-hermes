package vega.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DocumentTest {

    @Test
    void opensAtVersionOne() {
        Document doc = Document.opened("file:///Large.java", "class A {}");

        assertEquals(1, doc.version());
        assertEquals("class A {}", doc.text());
    }

    @Test
    void applyingAnEditAdvancesVersionByExactlyOne() {
        Document doc = Document.opened("file:///Large.java", "class A {}");

        Document edited = doc.apply(Edit.replace(9, 9, "int x;", 1));

        assertEquals(2, edited.version());
        assertEquals("class A {int x;}", edited.text());
    }

    @Test
    void rejectsAnEditWhoseBaseVersionIsNotCurrent() {
        Document doc = Document.opened("file:///Large.java", "class A {}");

        // A stale edit must be refused rather than applied out of order: silently accepting it
        // would desynchronise the backend mirror from the editor buffer.
        assertThrows(
                IllegalStateException.class,
                () -> doc.apply(Edit.replace(0, 0, "x", 7)));
    }
}
