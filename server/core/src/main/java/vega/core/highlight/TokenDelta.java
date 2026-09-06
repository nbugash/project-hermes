package vega.core.highlight;

import java.util.List;

/**
 * A change to a previously sent semantic-token array, expressed as slice replacements.
 *
 * <p>Deliberately not an lsp4j type: the core does not depend on the protocol library (Constitution
 * Principle III), and the LSP adapter translates this at the boundary.
 *
 * @param resultId identifier the client quotes when asking for the next delta
 * @param edits slice replacements, with indices into the previous flat array
 */
public record TokenDelta(String resultId, List<Edit> edits) {

    /**
     * One slice replacement.
     *
     * <p>Indices are into the flat integer array, not token indices, and always land on a multiple
     * of five — the array is packed {@code deltaLine, deltaStart, length, type, modifiers}, so an
     * edit that straddles a boundary shifts every following field by one position and the client
     * paints nonsense rather than staleness.
     *
     * @param start index into the previous array where the replacement begins
     * @param deleteCount how many integers to remove at {@code start}
     * @param data integers to insert in their place
     */
    public record Edit(int start, int deleteCount, int[] data) {}
}
