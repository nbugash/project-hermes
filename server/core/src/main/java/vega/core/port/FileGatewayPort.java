package vega.core.port;

import vega.core.document.DocumentMetadata;

/**
 * All filesystem access for opened documents.
 *
 * <p>The editor process never reads or writes the document itself (FR-002, FR-021). Routing file
 * I/O through the backend is what makes a remote backend possible later without changing the flow,
 * and it is the behavioural guarantee that replaced the stubbed transport interface deleted from
 * this spike's scope.
 */
public interface FileGatewayPort {

    FileContent read(String uri);

    /**
     * Writes bytes for an already-verified document.
     *
     * @param expectedDiskHash digest the file on disk must currently have; a mismatch means the file
     *     changed underneath the editor and the write must be refused rather than silently clobber
     *     someone else's change.
     */
    WriteOutcome write(String uri, String text, DocumentMetadata metadata, String expectedDiskHash);

    record FileContent(String text, DocumentMetadata metadata) {}

    enum WriteOutcome {
        WRITTEN,
        DISK_CHANGED,
        FAILED
    }
}
