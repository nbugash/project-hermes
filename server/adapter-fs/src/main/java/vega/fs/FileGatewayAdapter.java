package vega.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import vega.core.document.DocumentMetadata;
import vega.core.document.LineEnding;
import vega.core.port.FileGatewayPort;

/**
 * Outbound adapter: the only code that touches the filesystem for opened documents.
 *
 * <p>Reading reports the byte-level characteristics the core cannot observe for itself — encoding,
 * line-ending style, trailing newline, digest — because a save has to reproduce them exactly
 * (SC-010).
 */
public final class FileGatewayAdapter implements FileGatewayPort {

    @Override
    public FileContent read(String uri) {
        Path path = Path.of(URI.create(uri));
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + uri, e);
        }

        // A file that is not valid UTF-8 is decoded as ISO-8859-1 instead. That mapping is
        // byte-for-byte reversible for every possible byte, so the file still round-trips exactly on
        // save even though its real encoding is unknown. Decoding it as UTF-8 with replacement
        // characters would corrupt it the first time the user pressed Ctrl+S.
        Charset charset = isValidUtf8(bytes) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        String raw = new String(bytes, charset);

        LineEnding lineEnding = detectLineEnding(raw);
        boolean hasTrailingNewline = raw.endsWith("\n");

        // The editor buffer works in LF; the original style is restored on save. Handing CRLF
        // through would put a stray \r at the end of every line in the buffer.
        String normalised = raw.replace("\r\n", "\n");

        return new FileContent(
                normalised, new DocumentMetadata(charset, lineEnding, hasTrailingNewline, sha256(bytes)));
    }

    @Override
    public WriteOutcome write(
            String uri, String text, DocumentMetadata metadata, String expectedDiskHash) {
        Path path = Path.of(URI.create(uri));

        byte[] onDisk;
        try {
            onDisk = Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
        } catch (IOException e) {
            return WriteOutcome.FAILED;
        }

        // Refuse rather than clobber. Between reading and saving, the file may have been changed by
        // a rebase, a formatter or another editor; overwriting silently discards that work, and the
        // user has no way to discover it happened.
        if (!sha256(onDisk).equals(expectedDiskHash)) {
            return WriteOutcome.DISK_CHANGED;
        }

        byte[] bytes = serialise(text, metadata, new String(onDisk, metadata.charset()));

        try {
            // Written to a sibling and moved into place: a crash mid-write would otherwise leave a
            // truncated source file, which is worse than not having saved at all.
            Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".vega-tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(temporary);
                throw e;
            }
        } catch (IOException e) {
            return WriteOutcome.FAILED;
        }

        return WriteOutcome.WRITTEN;
    }

    /**
     * Turns the LF-normalised buffer back into the file's own byte representation.
     *
     * <p>{@code MIXED} is reconstructed line by line from what was on disk, because the enum's
     * contract is that each line keeps the ending it had. Files end up mixed through merges, and
     * normalising one turns a one-line change into a whole-file diff on someone else's review.
     */
    private static byte[] serialise(String text, DocumentMetadata metadata, String onDisk) {
        String rendered =
                switch (metadata.lineEnding()) {
                    case LF -> text;
                    case CRLF -> text.replace("\n", "\r\n");
                    case MIXED -> restoreMixedLineEndings(text, onDisk);
                };
        return rendered.getBytes(metadata.charset());
    }

    private static String restoreMixedLineEndings(String text, String onDisk) {
        boolean[] wasCrlf = crlfByLine(onDisk);
        // New lines take the style the file already uses most, which is the least surprising choice
        // when there is no original ending to copy.
        boolean crlfDominates = countOccurrences(onDisk, "\r\n") * 2 > countOccurrences(onDisk, "\n");

        StringBuilder out = new StringBuilder(text.length() + 16);
        int line = 0;
        int index = 0;
        while (index < text.length()) {
            int newline = text.indexOf('\n', index);
            if (newline < 0) {
                out.append(text, index, text.length());
                break;
            }
            out.append(text, index, newline);
            // A line that existed keeps exactly the ending it had — including a bare LF among
            // CRLFs. Only lines beyond the original get the dominant style; conflating the two
            // rewrites every LF line in a mixed file, which is the failure this branch exists to
            // avoid.
            String ending =
                    line < wasCrlf.length
                            ? (wasCrlf[line] ? "\r\n" : "\n")
                            : (crlfDominates ? "\r\n" : "\n");
            out.append(ending);
            index = newline + 1;
            line++;
        }
        return out.toString();
    }

    /** Which lines of the original ended with CRLF, indexed from zero. */
    private static boolean[] crlfByLine(String onDisk) {
        int lines = countOccurrences(onDisk, "\n");
        boolean[] wasCrlf = new boolean[lines];
        int line = 0;
        for (int i = 0; i < onDisk.length() && line < lines; i++) {
            if (onDisk.charAt(i) == '\n') {
                wasCrlf[line++] = i > 0 && onDisk.charAt(i - 1) == '\r';
            }
        }
        return wasCrlf;
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException notUtf8) {
            return false;
        }
    }

    private static LineEnding detectLineEnding(String raw) {
        int crlf = countOccurrences(raw, "\r\n");
        int lf = countOccurrences(raw, "\n") - crlf;

        if (crlf > 0 && lf > 0) {
            return LineEnding.MIXED;
        }
        if (crlf > 0) {
            return LineEnding.CRLF;
        }
        // A file with no line breaks at all is treated as LF so that a save adds nothing.
        return LineEnding.LF;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }
}
