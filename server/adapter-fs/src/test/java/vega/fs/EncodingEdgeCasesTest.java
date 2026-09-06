package vega.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vega.core.document.LineEnding;

/**
 * The awkward files. Each of these is a real thing found in real repositories, and each one is a way
 * a save can quietly rewrite a file the user did not intend to change.
 */
class EncodingEdgeCasesTest {

    @TempDir Path tempDir;

    private final FileGatewayAdapter gateway = new FileGatewayAdapter();

    private Path fileOf(String name, byte[] bytes) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    @Test
    void detectsCrlfAsTheLineEndingStyle() throws Exception {
        Path file = fileOf("Crlf.java", "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(LineEnding.CRLF, gateway.read(file.toUri().toString()).metadata().lineEnding());
    }

    @Test
    void detectsMixedLineEndings() throws Exception {
        Path file = fileOf("Mixed.java", "a\r\nb\nc\r\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(LineEnding.MIXED, gateway.read(file.toUri().toString()).metadata().lineEnding());
    }

    @Test
    void mixedEndingsSurviveAnEditToOneLine() throws Exception {
        byte[] original = "first\r\nsecond\nthird\r\n".getBytes(StandardCharsets.UTF_8);
        Path file = fileOf("Mixed.java", original);
        var content = gateway.read(file.toUri().toString());

        gateway.write(
                file.toUri().toString(),
                content.text().replace("second", "SECOND"),
                content.metadata(),
                content.metadata().contentHash());

        // Each surviving line keeps the ending it had; only the text changed.
        assertEquals(
                "first\r\nSECOND\nthird\r\n",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void aFileThatIsNotValidUtf8RoundTripsByteForByte() throws Exception {
        // Latin-1 accented bytes: 0xE9 is a valid ISO-8859-1 'é' and an invalid UTF-8 sequence.
        byte[] original = {'c', 'a', 'f', (byte) 0xE9, '\n'};
        Path file = fileOf("Latin1.java", original);

        var content = gateway.read(file.toUri().toString());
        assertEquals(StandardCharsets.ISO_8859_1, content.metadata().charset());

        gateway.write(
                file.toUri().toString(),
                content.text(),
                content.metadata(),
                content.metadata().contentHash());

        // Decoding this as UTF-8 would substitute a replacement character and destroy the byte on
        // the first save. ISO-8859-1 is reversible for every byte, so the file survives even though
        // its true encoding is unknown.
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void detectsAMissingTrailingNewline() throws Exception {
        Path file = fileOf("Bare.java", "no newline at end".getBytes(StandardCharsets.UTF_8));

        assertFalse(gateway.read(file.toUri().toString()).metadata().hasTrailingNewline());
    }

    @Test
    void detectsAPresentTrailingNewline() throws Exception {
        Path file = fileOf("Ends.java", "ends with newline\n".getBytes(StandardCharsets.UTF_8));

        assertTrue(gateway.read(file.toUri().toString()).metadata().hasTrailingNewline());
    }

    @Test
    void refusesToWriteWhenTheFileChangedOnDiskSinceItWasRead() throws Exception {
        Path file = fileOf("Raced.java", "original\n".getBytes(StandardCharsets.UTF_8));
        var content = gateway.read(file.toUri().toString());

        Files.writeString(file, "changed by someone else\n");

        var outcome =
                gateway.write(
                        file.toUri().toString(),
                        content.text(),
                        content.metadata(),
                        content.metadata().contentHash());

        assertEquals(vega.core.port.FileGatewayPort.WriteOutcome.DISK_CHANGED, outcome);
        // The other change must survive: refusing has to mean refusing, not refusing after writing.
        assertEquals("changed by someone else\n", Files.readString(file));
    }
}
