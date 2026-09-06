package vega.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * tree-sitter addresses source in UTF-8 bytes; the core and LSP address it in UTF-16 code units.
 * They coincide for ASCII and diverge everywhere else, so this translation is the single point where
 * a mistake turns into silently misplaced highlighting rather than a crash.
 */
class Utf8Utf16OffsetsTest {

    private static Utf8Utf16Offsets of(String text) {
        return Utf8Utf16Offsets.of(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void asciiDocumentsTranslateAsIdentity() {
        Utf8Utf16Offsets offsets = of("class A { int x; }");

        assertTrue(offsets.isAscii());
        assertEquals(0, offsets.toUtf16(0));
        assertEquals(9, offsets.toUtf16(9));
        assertEquals(9, offsets.toByte(9));
    }

    @Test
    void twoByteCharacterShiftsLaterOffsetsByOne() {
        // "é" is one UTF-16 unit and two UTF-8 bytes.
        Utf8Utf16Offsets offsets = of("aéb");

        assertFalse(offsets.isAscii());
        assertEquals(0, offsets.toUtf16(0));
        assertEquals(1, offsets.toUtf16(1));
        assertEquals(2, offsets.toUtf16(3));
    }

    @Test
    void threeByteCharacterShiftsLaterOffsetsByTwo() {
        // "€" is one UTF-16 unit and three UTF-8 bytes.
        Utf8Utf16Offsets offsets = of("a€b");

        assertEquals(1, offsets.toUtf16(1));
        assertEquals(2, offsets.toUtf16(4));
    }

    @Test
    void astralCharacterIsTwoUtf16UnitsAndFourBytes() {
        // "😀" is a surrogate pair in UTF-16 and four bytes in UTF-8.
        Utf8Utf16Offsets offsets = of("a😀b");

        assertEquals(1, offsets.toUtf16(1));
        assertEquals(3, offsets.toUtf16(5));
    }

    @Test
    void translationRoundTripsForEveryCharacterBoundary() {
        String text = "aéb€c😀d";
        Utf8Utf16Offsets offsets = of(text);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        int byteOffset = 0;
        for (int i = 0; i < text.length(); ) {
            assertEquals(i, offsets.toUtf16(byteOffset), "utf16 at byte " + byteOffset);
            assertEquals(byteOffset, offsets.toByte(i), "byte at utf16 " + i);
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            byteOffset += new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
        }
        assertEquals(text.length(), offsets.toUtf16(bytes.length));
    }

    @Test
    void endOfDocumentTranslatesToTheTextLength() {
        String text = "café";
        assertEquals(text.length(), of(text).toUtf16(text.getBytes(StandardCharsets.UTF_8).length));
    }
}
