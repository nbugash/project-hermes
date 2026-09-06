package vega.core.highlight;

/**
 * One styled span.
 *
 * @param start offset from the start of the document
 * @param length span length
 * @param type style classification, resolved against the protocol legend by the adapter
 */
public record Token(int start, int length, String type) {

    public Token {
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0, was " + start);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be > 0, was " + length);
        }
    }

    public int end() {
        return start + length;
    }
}
