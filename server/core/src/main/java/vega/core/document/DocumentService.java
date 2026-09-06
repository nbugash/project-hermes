package vega.core.document;

import java.util.HashMap;
import java.util.Map;
import vega.core.port.FileGatewayPort;

/**
 * Tracks open documents and applies edits to them.
 *
 * <p>This is the backend's mirror of the editor's buffer, kept in step by incremental changes. It
 * exists so the backend can parse; the editor remains the authoritative owner of the text while a
 * document is open (FR-007).
 *
 * <p>Not thread-safe by design. All mutation is funnelled through the protocol handler, which
 * processes notifications for a document in order; adding locking here would invite the assumption
 * that out-of-order application is safe, which it is not.
 */
public final class DocumentService {

    private final FileGatewayPort fileGateway;
    private final Map<String, Document> open = new HashMap<>();

    public DocumentService(FileGatewayPort fileGateway) {
        this.fileGateway = fileGateway;
    }

    public Document open(String uri) {
        FileGatewayPort.FileContent content = fileGateway.read(uri);
        Document document = Document.opened(uri, content.text(), content.metadata());
        open.put(uri, document);
        return document;
    }

    /**
     * Registers a document from text the editor supplied, rather than by reading the file.
     *
     * <p>{@code didOpen} carries the buffer's contents and the editor owns them while a document is
     * open (FR-007), so this is the authoritative registration. Depending on an earlier
     * {@code vega/readDocument} instead would leave the mirror empty for any client that re-opens a
     * document or opens one it already had in memory, and every subsequent edit would fail.
     *
     * <p>Metadata from a prior read is preserved: encoding and line endings are properties of the
     * file that the editor never sees and cannot re-supply.
     */
    public Document openFromEditor(String uri, String text) {
        Document existing = open.get(uri);
        Document document =
                existing == null
                        ? Document.opened(uri, text)
                        : Document.opened(uri, text, existing.metadata());
        open.put(uri, document);
        return document;
    }

    public Document current(String uri) {
        Document document = open.get(uri);
        if (document == null) {
            throw new IllegalStateException("Document is not open: " + uri);
        }
        return document;
    }

    public Document applyEdit(String uri, Edit edit) {
        Document updated = current(uri).apply(edit);
        open.put(uri, updated);
        return updated;
    }

    public void close(String uri) {
        open.remove(uri);
    }
}
