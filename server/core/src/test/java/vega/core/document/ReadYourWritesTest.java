package vega.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import vega.core.port.FileGatewayPort;

/**
 * Constitution Principle IV: an operation issued immediately after an edit must observe that edit.
 *
 * <p>The mirror is authoritative for analysis the instant an edit is applied — there is no flush,
 * queue drain or debounce between applying an edit and being able to read it back. A design that
 * needed one would make correctness depend on timing, and the bug it produces is highlighting
 * computed against text the user has already changed.
 */
class ReadYourWritesTest {

    private static DocumentService serviceWith(String text) {
        DocumentService service = new DocumentService(new FixedGateway(text));
        service.open("file:///A.java");
        return service;
    }

    @Test
    void aReadImmediatelyAfterAnEditSeesThatEdit() {
        DocumentService service = serviceWith("class A {}");
        int version = service.current("file:///A.java").version();

        service.applyEdit("file:///A.java", Edit.replace(9, 9, " int x;", version));

        assertEquals("class A { int x;}", service.current("file:///A.java").text());
    }

    @Test
    void consecutiveEditsEachObserveTheOneBefore() {
        DocumentService service = serviceWith("");

        for (int i = 0; i < 10; i++) {
            Document before = service.current("file:///A.java");
            service.applyEdit("file:///A.java", Edit.replace(before.text().length(), before.text().length(), "x", before.version()));
        }

        assertEquals("xxxxxxxxxx", service.current("file:///A.java").text());
    }

    @Test
    void theVersionReadBackMatchesTheEditThatProducedIt() {
        DocumentService service = serviceWith("class A {}");
        int version = service.current("file:///A.java").version();

        Document returned = service.applyEdit("file:///A.java", Edit.replace(0, 0, "a", version));

        // The returned document and the stored one must be the same revision; if they can differ,
        // a caller acting on the return value is acting on something the service will not serve.
        assertEquals(returned.version(), service.current("file:///A.java").version());
        assertEquals(returned.text(), service.current("file:///A.java").text());
    }

    private record FixedGateway(String text) implements FileGatewayPort {
        @Override
        public FileContent read(String uri) {
            return new FileContent(
                    text,
                    new DocumentMetadata(StandardCharsets.UTF_8, LineEnding.LF, false, "hash"));
        }

        @Override
        public WriteOutcome write(
                String uri, String content, DocumentMetadata metadata, String expectedDiskHash) {
            return WriteOutcome.FAILED;
        }
    }
}
