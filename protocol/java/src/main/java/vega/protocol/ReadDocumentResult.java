package vega.protocol;

/**
 * Result of {@code vega/readDocument}: the document's text plus the metadata needed to write it back
 * byte-identically.
 *
 * <p>The metadata travels with the text deliberately. Encoding, line ending and trailing-newline
 * state are properties of the file the editor never sees, and a save that guesses them reformats
 * the whole file — a diff the user never asked for.
 */
public class ReadDocumentResult {

    private String text;
    private String encoding;
    private String lineEnding;
    private boolean hasTrailingNewline;
    private String contentHash;

    public ReadDocumentResult() {}

    public ReadDocumentResult(
            String text,
            String encoding,
            String lineEnding,
            boolean hasTrailingNewline,
            String contentHash) {
        this.text = text;
        this.encoding = encoding;
        this.lineEnding = lineEnding;
        this.hasTrailingNewline = hasTrailingNewline;
        this.contentHash = contentHash;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public String getLineEnding() {
        return lineEnding;
    }

    public void setLineEnding(String lineEnding) {
        this.lineEnding = lineEnding;
    }

    public boolean isHasTrailingNewline() {
        return hasTrailingNewline;
    }

    public void setHasTrailingNewline(boolean hasTrailingNewline) {
        this.hasTrailingNewline = hasTrailingNewline;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }
}
