package vega.core.document;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The document being edited.
 *
 * <p>Ownership is split deliberately: the editor process owns the authoritative buffer while a
 * document is open, and the backend keeps this mirror synchronised by incremental changes purely so
 * it can parse. See specs/001-large-file-responsiveness/data-model.md.
 *
 * <p>The text is held as a {@link PieceText} rather than a {@code String}. Applying an edit to a
 * String rebuilds the whole document — 1.8 MB per keystroke on the measured corpus — which is
 * O(file size) work on the per-keystroke path and exactly what Principle VI forbids. Pieces are
 * shared instead, and the flat String is materialised only when something actually asks for it:
 * saving, hashing and full-document tokenization, none of which happen per keystroke.
 *
 * <p>A class rather than a record because that materialisation is cached, and a record's generated
 * accessors cannot be lazy. Immutability is unchanged and remains load-bearing: an in-flight
 * highlight finishes against the version it started on because that version cannot change.
 */
public final class Document {

    private static final DocumentMetadata UNKNOWN_METADATA =
            new DocumentMetadata(StandardCharsets.UTF_8, LineEnding.LF, true, "");

    private final String uri;
    private final PieceText content;
    private final int version;
    private final DocumentMetadata metadata;

    private Document(String uri, PieceText content, int version, DocumentMetadata metadata) {
        this.uri = uri;
        this.content = content;
        this.version = version;
        this.metadata = metadata;
    }

    /** Opens a document whose byte characteristics are not known, for tests and in-memory use. */
    public static Document opened(String uri, String text) {
        return new Document(uri, PieceText.of(text), 1, UNKNOWN_METADATA);
    }

    public static Document opened(String uri, String text, DocumentMetadata metadata) {
        return new Document(uri, PieceText.of(text), 1, metadata);
    }

    public String uri() {
        return uri;
    }

    /**
     * The text, flattened.
     *
     * <p>Materialises the pieces, so callers on the per-keystroke path should use {@link #content()}
     * instead. Saving, hashing and whole-document tokenization all legitimately want a String and
     * none of them run per keystroke.
     */
    public String text() {
        return content.toString();
    }

    /** The text without flattening it. Prefer this anywhere an edit-rate operation reads the text. */
    public CharSequence content() {
        return content;
    }

    public int version() {
        return version;
    }

    public DocumentMetadata metadata() {
        return metadata;
    }

    /**
     * Applies an edit, producing the next version.
     *
     * @throws IllegalStateException if the edit does not apply to this exact version. Rejecting is
     *     required: applying a stale edit would desynchronise this mirror from the editor's buffer,
     *     and the divergence would only surface later as corrupted highlighting or a bad save.
     */
    public Document apply(Edit edit) {
        if (edit.baseVersion() != version) {
            throw new IllegalStateException(
                    "Edit applies to version " + edit.baseVersion() + " but document is at " + version);
        }
        if (edit.end() > content.length()) {
            throw new StringIndexOutOfBoundsException(
                    "Edit range " + edit.start() + ".." + edit.end() + " exceeds length " + content.length());
        }
        return new Document(
                uri, content.replace(edit.start(), edit.end(), edit.newText()), version + 1, metadata);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Document document
                && version == document.version
                && uri.equals(document.uri)
                && metadata.equals(document.metadata)
                && text().equals(document.text());
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, version, metadata, text());
    }

    @Override
    public String toString() {
        return "Document[uri=" + uri + ", version=" + version + ", length=" + content.length() + "]";
    }
}
