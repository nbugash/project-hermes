package vega.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Saving a document nobody edited must not change a single byte.
 *
 * <p>This is the sharpest test of the whole save path, because it fails for any serialisation
 * mistake at all: a normalised line ending, a re-encoded character, an added trailing newline. It is
 * also the case a user is least willing to forgive — an accidental Ctrl+S should be invisible.
 */
class UnmodifiedSaveTest {

    @TempDir Path tempDir;

    private final FileGatewayAdapter gateway = new FileGatewayAdapter();

    private void assertUnmodifiedSaveIsByteIdentical(String name, byte[] original) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, original);

        var content = gateway.read(file.toUri().toString());
        gateway.write(
                file.toUri().toString(),
                content.text(),
                content.metadata(),
                content.metadata().contentHash());

        assertArrayEquals(original, Files.readAllBytes(file), name + " changed on an unmodified save");
    }

    @Test
    void lfFile() throws Exception {
        assertUnmodifiedSaveIsByteIdentical("Lf.java", "class A {\n    int x;\n}\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void crlfFile() throws Exception {
        assertUnmodifiedSaveIsByteIdentical(
                "Crlf.java", "class A {\r\n    int x;\r\n}\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fileWithoutTrailingNewline() throws Exception {
        assertUnmodifiedSaveIsByteIdentical("Bare.java", "class A {}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void mixedLineEndings() throws Exception {
        // Each line keeps the ending it had. A file like this usually got that way through a merge,
        // and normalising it turns a one-line change into a whole-file diff.
        assertUnmodifiedSaveIsByteIdentical(
                "Mixed.java", "class A {\r\n    int x;\n    int y;\r\n}\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fileContainingNonAsciiText() throws Exception {
        assertUnmodifiedSaveIsByteIdentical(
                "Unicode.java",
                "class A {\n    String s = \"café 😀\";\n}\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyFile() throws Exception {
        assertUnmodifiedSaveIsByteIdentical("Empty.java", new byte[0]);
    }
}
