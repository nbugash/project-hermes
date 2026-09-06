package vega.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Byte-level characteristics are captured at open and are inputs to save. They are carried on the
 * document because whoever read the bytes is the only party that can report them, and SC-010
 * requires a save to reproduce them exactly.
 */
class DocumentMetadataTest {

    @Test
    void carriesTheMetadataItWasOpenedWith() {
        DocumentMetadata metadata =
                new DocumentMetadata(StandardCharsets.UTF_8, LineEnding.CRLF, false, "abc123");

        Document doc = Document.opened("file:///A.java", "class A {}", metadata);

        assertEquals(LineEnding.CRLF, doc.metadata().lineEnding());
        assertEquals(StandardCharsets.UTF_8, doc.metadata().charset());
        assertTrue(!doc.metadata().hasTrailingNewline());
    }

    @Test
    void editingDoesNotAlterMetadata() {
        DocumentMetadata metadata =
                new DocumentMetadata(StandardCharsets.UTF_8, LineEnding.CRLF, true, "abc123");
        Document doc = Document.opened("file:///A.java", "class A {}", metadata);

        Document edited = doc.apply(Edit.replace(9, 9, "int x;", 1));

        // Editing text must never silently convert line endings or drop a trailing newline;
        // that would make a save byte-different from the file that was opened.
        assertEquals(metadata, edited.metadata());
    }
}
