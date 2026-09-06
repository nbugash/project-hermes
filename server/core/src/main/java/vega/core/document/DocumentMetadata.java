package vega.core.document;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Byte-level characteristics of the file as it was read from disk.
 *
 * <p>These are inputs to save, not editable state. The backend is the only party that sees the
 * bytes, so it is the only party that can report them; the editor round-trips them untouched.
 *
 * @param contentHash digest of the bytes read, used to detect on-disk change before writing
 */
public record DocumentMetadata(
        Charset charset, LineEnding lineEnding, boolean hasTrailingNewline, String contentHash) {

    public DocumentMetadata {
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(lineEnding, "lineEnding");
        Objects.requireNonNull(contentHash, "contentHash");
    }
}
