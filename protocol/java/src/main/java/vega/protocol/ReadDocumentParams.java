package vega.protocol;

/**
 * Parameters for {@code vega/readDocument}.
 *
 * <p>A mutable class with a no-argument constructor rather than a record: lsp4j deserialises
 * parameters with Gson, which needs to construct the instance before it has the values.
 */
public class ReadDocumentParams {

    private String uri;

    public ReadDocumentParams() {}

    public ReadDocumentParams(String uri) {
        this.uri = uri;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }
}
