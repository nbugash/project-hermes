package vega.core.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every analysis result carries the document version it was computed from. That single field is
 * what makes asynchronous highlighting safe under fast typing (FR-010).
 */
class HighlightResultTest {

    @Test
    void resultForTheCurrentVersionIsApplicable() {
        HighlightResult result = HighlightResult.full(7, "r1", List.of(new Token(0, 5, "keyword")));

        assertTrue(result.appliesTo(7));
    }

    @Test
    void resultForAnOlderVersionIsNotApplicable() {
        HighlightResult result = HighlightResult.full(7, "r1", List.of(new Token(0, 5, "keyword")));

        // The developer kept typing while this was being computed. Painting it would show
        // highlighting for text that no longer exists.
        assertFalse(result.appliesTo(8));
    }

    @Test
    void rejectsOverlappingTokens() {
        // Overlap is rejected at construction so token application stays a single ordered pass.
        assertThrows(
                IllegalArgumentException.class,
                () -> HighlightResult.full(1, "r1", List.of(new Token(0, 5, "a"), new Token(3, 5, "b"))));
    }

    @Test
    void rejectsOutOfOrderTokens() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HighlightResult.full(1, "r1", List.of(new Token(10, 2, "a"), new Token(0, 2, "b"))));
    }

    @Test
    void exposesTokensInOrder() {
        HighlightResult result =
                HighlightResult.full(1, "r1", List.of(new Token(0, 5, "keyword"), new Token(6, 3, "type")));

        assertEquals(2, result.tokens().size());
        assertEquals(0, result.tokens().get(0).start());
        assertEquals(6, result.tokens().get(1).start());
    }
}
