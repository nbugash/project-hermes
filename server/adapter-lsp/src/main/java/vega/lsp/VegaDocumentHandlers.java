package vega.lsp;

import java.util.concurrent.CompletableFuture;
import vega.core.document.Document;
import vega.core.document.DocumentMetadata;
import vega.core.document.DocumentService;
import vega.core.obs.Correlation;
import vega.core.document.MirrorVerification;
import vega.core.port.FileGatewayPort;
import vega.protocol.ReadDocumentParams;
import vega.protocol.ReadDocumentResult;
import vega.protocol.SaveDocumentParams;
import vega.protocol.SaveDocumentResult;

/**
 * Handles {@code vega/readDocument}: the editor asks the backend for a document's bytes rather than
 * reading the file itself.
 *
 * <p>Reading deliberately does <em>not</em> parse. The editor calls this to obtain text it can put
 * on screen; parsing is triggered later by {@code didOpen}, once the editor has actually opened the
 * document. Folding the parse in here would put whole-file work on the cold-start path before
 * anything has asked for highlighting, which is exactly the cost the cold-start budget forbids.
 */
public final class VegaDocumentHandlers {

    private final DocumentService documents;
    private final FileGatewayPort files;

    public VegaDocumentHandlers(DocumentService documents, FileGatewayPort files) {
        this.documents = documents;
        this.files = files;
    }

    public CompletableFuture<ReadDocumentResult> readDocument(ReadDocumentParams params) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (var scope = Correlation.beginScope(Correlation.newId())) {
                        Document document = documents.open(params.getUri());
                        DocumentMetadata metadata = document.metadata();
                        return new ReadDocumentResult(
                                document.text(),
                                metadata.charset().name(),
                                metadata.lineEnding().name(),
                                metadata.hasTrailingNewline(),
                                metadata.contentHash());
                    }
                });
    }

    /**
     * Handles {@code vega/saveDocument}: writes the backend's own mirror to disk.
     *
     * <p>Two checks precede the write, in this order. The mirror must match the editor's buffer, or
     * the bytes written would be text the user never saw. Then the file on disk must be what it was
     * when read, or the write would silently discard someone else's change. Both are reported as
     * outcomes rather than errors, because the editor has to say something specific and keep the
     * user's buffer either way.
     */
    public CompletableFuture<SaveDocumentResult> saveDocument(SaveDocumentParams params) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (var scope = Correlation.beginScope(Correlation.newId())) {
                        Document document = documents.current(params.getUri());

                        if (!MirrorVerification.matches(document.text(), params.getExpectedContentHash())) {
                            return new SaveDocumentResult(
                                    SaveDocumentResult.MIRROR_MISMATCH,
                                    document.version(),
                                    null,
                                    "The backend's copy of the document does not match the editor's buffer");
                        }

                        FileGatewayPort.WriteOutcome outcome =
                                files.write(
                                        params.getUri(),
                                        document.text(),
                                        document.metadata(),
                                        params.getBaseContentHash());

                        return switch (outcome) {
                            case WRITTEN ->
                                    new SaveDocumentResult(
                                            SaveDocumentResult.WRITTEN,
                                            document.version(),
                                            MirrorVerification.hash(document.text()),
                                            null);
                            case DISK_CHANGED ->
                                    new SaveDocumentResult(
                                            SaveDocumentResult.DISK_CHANGED,
                                            document.version(),
                                            null,
                                            "The file changed on disk since it was opened");
                            case FAILED ->
                                    new SaveDocumentResult(
                                            SaveDocumentResult.FAILED,
                                            document.version(),
                                            null,
                                            "The file could not be written");
                        };
                    } catch (IllegalStateException notOpen) {
                        return new SaveDocumentResult(
                                SaveDocumentResult.FAILED, null, null, notOpen.getMessage());
                    }
                });
    }
}
