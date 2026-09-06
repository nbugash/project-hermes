package vega.core.highlight;

import java.util.List;
import java.util.Objects;

/**
 * Styling computed for a document, keyed to the exact version it was computed from.
 *
 * <p>Tokens are validated ordered and non-overlapping at construction so that applying them stays a
 * single forward pass. Enforcing it here rather than at the adapter means a violation surfaces in a
 * core unit test instead of as visually wrong highlighting.
 *
 * @param documentVersion version this result describes
 * @param resultId identifier a later delta can be expressed against
 */
public record HighlightResult(int documentVersion, String resultId, List<Token> tokens) {

    public HighlightResult {
        Objects.requireNonNull(resultId, "resultId");
        tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
        validateOrderedAndDisjoint(tokens);
    }

    public static HighlightResult full(int documentVersion, String resultId, List<Token> tokens) {
        return new HighlightResult(documentVersion, resultId, tokens);
    }

    /**
     * Whether this result may be painted against the given document version.
     *
     * <p>Anything older is discarded rather than painted: the developer has typed since it was
     * requested, so its offsets describe text that no longer exists.
     */
    public boolean appliesTo(int currentDocumentVersion) {
        return documentVersion == currentDocumentVersion;
    }

    private static void validateOrderedAndDisjoint(List<Token> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            Token previous = tokens.get(i - 1);
            Token current = tokens.get(i);
            if (current.start() < previous.end()) {
                throw new IllegalArgumentException(
                        "Tokens must be ordered and non-overlapping: "
                                + previous
                                + " then "
                                + current);
            }
        }
    }
}
