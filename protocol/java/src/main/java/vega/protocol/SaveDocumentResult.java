package vega.protocol;

/**
 * Result of {@code vega/saveDocument}.
 *
 * <p>Every way a save can fail is a named status rather than an error response. The editor has to
 * tell the user something specific and keep their buffer either way, and "disk changed" and "mirror
 * drifted" call for different actions — one is someone else's edit, the other is a bug in
 * synchronisation.
 */
public class SaveDocumentResult {

    /** Bytes were written. */
    public static final String WRITTEN = "written";

    /** The backend's mirror does not match the editor's buffer; nothing was written. */
    public static final String MIRROR_MISMATCH = "mirror-mismatch";

    /** The file changed on disk since it was read; nothing was written. */
    public static final String DISK_CHANGED = "disk-changed";

    /** The write itself failed — permissions, a full disk, a vanished directory. */
    public static final String FAILED = "failed";

    private String status;
    private Integer version;
    private String contentHash;
    private String reason;

    public SaveDocumentResult() {}

    public SaveDocumentResult(String status, Integer version, String contentHash, String reason) {
        this.status = status;
        this.version = version;
        this.contentHash = contentHash;
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
