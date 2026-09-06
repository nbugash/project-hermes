package vega.protocol;

/**
 * Parameters for {@code vega/saveDocument}.
 *
 * <p>Carries no text. The backend already holds a synchronised mirror, so sending 50,000 lines back
 * for it to write bytes it has would be pure waste. The two hashes are what make that safe:
 * {@code expectedContentHash} proves the mirror matches the editor's buffer, and
 * {@code baseContentHash} proves the file on disk has not changed underneath both of them.
 */
public class SaveDocumentParams {

    private String uri;
    private int version;
    private String expectedContentHash;
    private String baseContentHash;

    public SaveDocumentParams() {}

    public SaveDocumentParams(String uri, int version, String expectedContentHash, String baseContentHash) {
        this.uri = uri;
        this.version = version;
        this.expectedContentHash = expectedContentHash;
        this.baseContentHash = baseContentHash;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getExpectedContentHash() {
        return expectedContentHash;
    }

    public void setExpectedContentHash(String expectedContentHash) {
        this.expectedContentHash = expectedContentHash;
    }

    public String getBaseContentHash() {
        return baseContentHash;
    }

    public void setBaseContentHash(String baseContentHash) {
        this.baseContentHash = baseContentHash;
    }
}
