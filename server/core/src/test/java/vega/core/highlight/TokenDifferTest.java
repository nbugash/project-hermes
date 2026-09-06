package vega.core.highlight;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Semantic-token deltas replace slices of the previous flat token array.
 *
 * <p>Every edit must land on a five-integer token boundary: the array is a packed sequence of
 * {@code deltaLine, deltaStart, length, type, modifiers} groups, and an edit that straddles a
 * boundary re-interprets every following integer as a different field. The result is not a stale
 * highlight but a scrambled one.
 */
class TokenDifferTest {

    /** Applies a delta the way a conforming client does, so the assertions test real usability. */
    private static int[] apply(int[] previous, TokenDelta delta) {
        List<Integer> working = new ArrayList<>();
        for (int value : previous) {
            working.add(value);
        }
        // Back-to-front, because each edit's indices refer to the array as it was before any edit.
        List<TokenDelta.Edit> edits = new ArrayList<>(delta.edits());
        edits.sort((a, b) -> Integer.compare(b.start(), a.start()));
        for (TokenDelta.Edit edit : edits) {
            for (int i = 0; i < edit.deleteCount(); i++) {
                working.remove(edit.start());
            }
            for (int i = edit.data().length - 1; i >= 0; i--) {
                working.add(edit.start(), edit.data()[i]);
            }
        }
        int[] result = new int[working.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = working.get(i);
        }
        return result;
    }

    private static int[] tokens(int count) {
        int[] data = new int[count * 5];
        for (int i = 0; i < count; i++) {
            data[i * 5] = 1;
            data[i * 5 + 1] = 0;
            data[i * 5 + 2] = 4;
            data[i * 5 + 3] = i % 3;
            data[i * 5 + 4] = 0;
        }
        return data;
    }

    @Test
    void identicalTokenArraysProduceNoEdits() {
        TokenDelta delta = TokenDiffer.diff(tokens(10), tokens(10));

        assertTrue(delta.edits().isEmpty(), "an unchanged document must cost nothing to transmit");
    }

    @Test
    void aChangeInTheMiddleProducesOneBoundedEdit() {
        int[] previous = tokens(10);
        int[] next = tokens(10);
        next[5 * 5 + 3] = 7;

        TokenDelta delta = TokenDiffer.diff(previous, next);

        assertEquals(1, delta.edits().size());
        TokenDelta.Edit edit = delta.edits().get(0);
        assertEquals(0, edit.start() % 5, "edit must start on a token boundary");
        assertEquals(0, edit.deleteCount() % 5, "edit must delete whole tokens");
        assertTrue(edit.deleteCount() <= 5, "one changed token should not resend the document");
        assertArrayEquals(next, apply(previous, delta));
    }

    @Test
    void appendedTokensProduceAnInsertAtTheEnd() {
        int[] previous = tokens(5);
        int[] next = tokens(7);

        TokenDelta delta = TokenDiffer.diff(previous, next);

        assertEquals(1, delta.edits().size());
        assertEquals(0, delta.edits().get(0).deleteCount());
        assertArrayEquals(next, apply(previous, delta));
    }

    @Test
    void removedTokensProduceADeletion() {
        int[] previous = tokens(7);
        int[] next = tokens(5);

        TokenDelta delta = TokenDiffer.diff(previous, next);

        assertEquals(1, delta.edits().size());
        assertEquals(0, delta.edits().get(0).data().length);
        assertArrayEquals(next, apply(previous, delta));
    }

    @Test
    void anEmptyPreviousResultSendsEverything() {
        int[] next = tokens(4);

        TokenDelta delta = TokenDiffer.diff(new int[0], next);

        assertArrayEquals(next, apply(new int[0], delta));
    }

    @Test
    void anEmptyNewResultDeletesEverything() {
        int[] previous = tokens(4);

        TokenDelta delta = TokenDiffer.diff(previous, new int[0]);

        assertArrayEquals(new int[0], apply(previous, delta));
    }

    @Test
    void deltasReconstructTheNewArrayAcrossManyShapes() {
        // The property that matters: whatever the diff chooses to emit, a conforming client that
        // applies it must arrive at exactly the new token array. Anything else is a scrambled
        // highlight that no amount of later edits will correct.
        for (int previousCount = 0; previousCount <= 12; previousCount++) {
            for (int nextCount = 0; nextCount <= 12; nextCount++) {
                int[] previous = tokens(previousCount);
                int[] next = tokens(nextCount);
                if (nextCount > 3) {
                    next[3 * 5 + 3] = 9;
                }

                TokenDelta delta = TokenDiffer.diff(previous, next);

                assertArrayEquals(
                        next, apply(previous, delta), previousCount + " -> " + nextCount + " tokens");
                for (TokenDelta.Edit edit : delta.edits()) {
                    assertEquals(0, edit.start() % 5, "start on token boundary");
                    assertEquals(0, edit.deleteCount() % 5, "delete whole tokens");
                    assertEquals(0, edit.data().length % 5, "insert whole tokens");
                }
            }
        }
    }
}
