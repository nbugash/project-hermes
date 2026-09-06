package vega.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vega.core.document.DocumentMetadata;
import vega.core.document.DocumentService;
import vega.core.document.LineEnding;
import vega.core.document.MirrorVerification;
import vega.core.port.FileGatewayPort;
import vega.protocol.SaveDocumentParams;
import vega.protocol.SaveDocumentResult;

/**
 * Every outcome of {@code vega/saveDocument}, including the ones a real filesystem will not produce
 * on demand.
 *
 * <p>A programmable gateway is used deliberately: a write failure from a full disk or a revoked
 * permission is exactly the case that must behave correctly and exactly the case a test against a
 * real temp directory cannot reliably create. Byte-exactness is covered separately, against the real
 * filesystem, in the adapter's own tests.
 */
class SaveRoundTripIntegrationTest {

    private static final String URI = "file:///A.java";
    private static final String TEXT = "class A {\n    int x;\n}\n";

    private ProgrammableGateway gateway;
    private DocumentService documents;
    private VegaDocumentHandlers handlers;

    @BeforeEach
    void setUp() {
        gateway = new ProgrammableGateway();
        documents = new DocumentService(gateway);
        documents.open(URI);
        handlers = new VegaDocumentHandlers(documents, gateway);
    }

    private SaveDocumentResult save(String expectedHash, String baseHash) throws Exception {
        return handlers.saveDocument(new SaveDocumentParams(URI, 1, expectedHash, baseHash)).get();
    }

    @Test
    void writesWhenTheMirrorMatchesAndDiskIsUnchanged() throws Exception {
        SaveDocumentResult result = save(MirrorVerification.hash(TEXT), "disk-hash");

        assertEquals(SaveDocumentResult.WRITTEN, result.getStatus());
        assertEquals(TEXT, gateway.written.get());
        assertNotNull(result.getContentHash(), "a successful save must report what it wrote");
        assertNull(result.getReason());
    }

    @Test
    void refusesWhenTheMirrorDoesNotMatchTheEditorsBuffer() throws Exception {
        SaveDocumentResult result = save(MirrorVerification.hash("something the editor has"), "disk-hash");

        assertEquals(SaveDocumentResult.MIRROR_MISMATCH, result.getStatus());
        // Nothing may be written: the bytes would be text the user never saw.
        assertNull(gateway.written.get());
        assertTrue(result.getReason().toLowerCase().contains("editor"));
    }

    @Test
    void refusesWhenNoHashIsSuppliedAtAll() throws Exception {
        // A client that proves nothing must not be trusted; otherwise the check is bypassable by
        // simply omitting the field.
        assertEquals(SaveDocumentResult.MIRROR_MISMATCH, save(null, "disk-hash").getStatus());
        assertNull(gateway.written.get());
    }

    @Test
    void reportsDiskChangedWhenTheFileMovedUnderneath() throws Exception {
        gateway.outcome = FileGatewayPort.WriteOutcome.DISK_CHANGED;

        SaveDocumentResult result = save(MirrorVerification.hash(TEXT), "stale-disk-hash");

        assertEquals(SaveDocumentResult.DISK_CHANGED, result.getStatus());
        assertTrue(result.getReason().toLowerCase().contains("disk"));
    }

    @Test
    void reportsFailureWhenTheWriteItselfFails() throws Exception {
        gateway.outcome = FileGatewayPort.WriteOutcome.FAILED;

        SaveDocumentResult result = save(MirrorVerification.hash(TEXT), "disk-hash");

        assertEquals(SaveDocumentResult.FAILED, result.getStatus());
        assertNotNull(result.getReason(), "a failure the user cannot act on is worse than no message");
    }

    @Test
    void reportsFailureRatherThanThrowingForADocumentThatIsNotOpen() throws Exception {
        SaveDocumentResult result =
                handlers
                        .saveDocument(new SaveDocumentParams("file:///Never.java", 1, "hash", "disk"))
                        .get();

        // An exception here would surface to the client as a protocol error with no status, leaving
        // the editor unable to say anything useful about the user's unsaved work.
        assertEquals(SaveDocumentResult.FAILED, result.getStatus());
    }

    @Test
    void reportsTheDocumentVersionThatWasWritten() throws Exception {
        SaveDocumentResult result = save(MirrorVerification.hash(TEXT), "disk-hash");

        assertEquals(documents.current(URI).version(), result.getVersion());
    }

    private static final class ProgrammableGateway implements FileGatewayPort {
        final AtomicReference<String> written = new AtomicReference<>();
        WriteOutcome outcome = WriteOutcome.WRITTEN;

        @Override
        public FileContent read(String uri) {
            return new FileContent(
                    TEXT,
                    new DocumentMetadata(StandardCharsets.UTF_8, LineEnding.LF, true, "disk-hash"));
        }

        @Override
        public WriteOutcome write(
                String uri, String text, DocumentMetadata metadata, String expectedDiskHash) {
            if (outcome == WriteOutcome.WRITTEN) {
                written.set(text);
            }
            return outcome;
        }
    }
}
