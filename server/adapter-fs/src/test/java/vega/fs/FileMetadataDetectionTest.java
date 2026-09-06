package vega.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vega.core.document.LineEnding;
import vega.core.port.FileGatewayPort;

/**
 * These characteristics can only be observed by whoever read the bytes, and SC-010 requires a save
 * to reproduce them exactly. Detecting them wrongly is invisible until a save silently rewrites
 * every line ending in someone's file.
 */
class FileMetadataDetectionTest {

    @TempDir Path tempDir;

    @Test
    void detectsUnixLineEndings() throws IOException {
        FileGatewayPort.FileContent content = readFileContaining("a\nb\nc\n");

        assertEquals(LineEnding.LF, content.metadata().lineEnding());
        assertTrue(content.metadata().hasTrailingNewline());
    }

    @Test
    void detectsWindowsLineEndings() throws IOException {
        FileGatewayPort.FileContent content = readFileContaining("a\r\nb\r\nc\r\n");

        assertEquals(LineEnding.CRLF, content.metadata().lineEnding());
    }

    @Test
    void detectsMixedLineEndings() throws IOException {
        // Mixed is a real case, not a defect. Normalising it would make an unmodified save
        // byte-different from the file that was opened.
        FileGatewayPort.FileContent content = readFileContaining("a\r\nb\nc\r\n");

        assertEquals(LineEnding.MIXED, content.metadata().lineEnding());
    }

    @Test
    void detectsAMissingTrailingNewline() throws IOException {
        FileGatewayPort.FileContent content = readFileContaining("a\nb");

        assertFalse(content.metadata().hasTrailingNewline());
    }

    @Test
    void treatsAFileWithNoNewlinesAsUnixSoASaveAddsNothing() throws IOException {
        FileGatewayPort.FileContent content = readFileContaining("single line");

        assertEquals(LineEnding.LF, content.metadata().lineEnding());
        assertFalse(content.metadata().hasTrailingNewline());
    }

    @Test
    void reportsAStableDigestOfTheBytesRead() throws IOException {
        FileGatewayPort.FileContent first = readFileContaining("class A {}\n");
        FileGatewayPort.FileContent second = readFileContaining("class A {}\n");

        assertEquals(first.metadata().contentHash(), second.metadata().contentHash());
        assertFalse(first.metadata().contentHash().isBlank());
    }

    @Test
    void reportsTextWithLineEndingsNormalisedForTheEditor() throws IOException {
        // The editor works in LF internally; the original style lives in metadata and is restored
        // on save. Handing CRLF to the editor would put \r inside every line of the buffer.
        FileGatewayPort.FileContent content = readFileContaining("a\r\nb\r\n");

        assertEquals("a\nb\n", content.text());
    }

    private FileGatewayPort.FileContent readFileContaining(String raw) throws IOException {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, raw, StandardCharsets.UTF_8);
        return new FileGatewayAdapter().read(file.toUri().toString());
    }
}
