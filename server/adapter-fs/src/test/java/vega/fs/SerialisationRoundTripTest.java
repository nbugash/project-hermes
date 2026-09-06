package vega.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vega.core.port.FileGatewayPort;

/**
 * A save must reproduce the file's byte-level character exactly.
 *
 * <p>Encoding, line-ending style and trailing-newline state are properties of the file that the
 * editor never sees. Guessing any of them reformats the whole file — a diff nobody asked for, which
 * on a shared repository lands on someone else's review.
 */
class SerialisationRoundTripTest {

    @TempDir Path tempDir;

    private final FileGatewayAdapter gateway = new FileGatewayAdapter();

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void lfFileWithTrailingNewlineRoundTripsUnchanged() throws Exception {
        Path file = write("Lf.java", "class A {\n    int x;\n}\n");
        var content = gateway.read(file.toUri().toString());

        FileGatewayPort.WriteOutcome outcome =
                gateway.write(
                        file.toUri().toString(),
                        content.text(),
                        content.metadata(),
                        content.metadata().contentHash());

        assertEquals(FileGatewayPort.WriteOutcome.WRITTEN, outcome);
        assertEquals("class A {\n    int x;\n}\n", Files.readString(file));
    }

    @Test
    void crlfFileIsWrittenBackWithCrlf() throws Exception {
        Path file = tempDir.resolve("Crlf.java");
        byte[] original = "class A {\r\n    int x;\r\n}\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        var content = gateway.read(file.toUri().toString());
        // The buffer is LF-normalised, which is what the editor works in.
        assertEquals("class A {\n    int x;\n}\n", content.text());

        gateway.write(
                file.toUri().toString(),
                content.text(),
                content.metadata(),
                content.metadata().contentHash());

        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void aFileWithNoTrailingNewlineDoesNotGainOne() throws Exception {
        Path file = write("NoNewline.java", "class A {}");
        var content = gateway.read(file.toUri().toString());

        gateway.write(
                file.toUri().toString(),
                content.text(),
                content.metadata(),
                content.metadata().contentHash());

        assertEquals("class A {}", Files.readString(file));
    }

    @Test
    void anEditedLfFileKeepsItsStyle() throws Exception {
        Path file = write("Edited.java", "class A {\n}\n");
        var content = gateway.read(file.toUri().toString());

        gateway.write(
                file.toUri().toString(),
                "class A {\n    int added;\n}\n",
                content.metadata(),
                content.metadata().contentHash());

        assertEquals("class A {\n    int added;\n}\n", Files.readString(file));
    }

    @Test
    void anEditedCrlfFileKeepsCrlfOnTheNewLineToo() throws Exception {
        Path file = tempDir.resolve("EditedCrlf.java");
        Files.write(file, "class A {\r\n}\r\n".getBytes(StandardCharsets.UTF_8));
        var content = gateway.read(file.toUri().toString());

        gateway.write(
                file.toUri().toString(),
                "class A {\n    int added;\n}\n",
                content.metadata(),
                content.metadata().contentHash());

        // A line added in a CRLF file must be written CRLF; a stray LF among CRLFs is the kind of
        // thing that shows up as a whole-file diff on the next commit.
        assertEquals(
                "class A {\r\n    int added;\r\n}\r\n",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
}
