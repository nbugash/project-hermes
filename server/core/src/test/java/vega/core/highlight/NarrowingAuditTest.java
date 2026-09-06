package vega.core.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The audit exists to answer a question with evidence rather than assumption: does edit-proximity
 * narrowing ever produce a different answer from a full re-query? Research asked for that rate to be
 * measured before any reconciliation pass is built (D13), so this counts rather than repairs.
 */
class NarrowingAuditTest {

    private static final ChangedRange WINDOW = new ChangedRange(100, 200);

    @Test
    void agreesWhenTheNarrowedResultMatchesTheFullQuery() {
        NarrowingAudit audit = new NarrowingAudit();
        List<Token> tokens = List.of(new Token(110, 5, "keyword"), new Token(120, 4, "variable"));

        assertTrue(audit.record(tokens, tokens, WINDOW));
        assertEquals(1, audit.comparisons());
        assertEquals(0, audit.disagreements());
    }

    @Test
    void ignoresFullQueryTokensOutsideTheWindow() {
        NarrowingAudit audit = new NarrowingAudit();
        List<Token> narrowed = List.of(new Token(110, 5, "keyword"));
        List<Token> full = List.of(new Token(10, 5, "keyword"), new Token(110, 5, "keyword"));

        // Narrowing never claimed to cover offset 10, so a token there is not a disagreement — the
        // audit would otherwise report a failure on every single edit and measure nothing.
        assertTrue(audit.record(narrowed, full, WINDOW));
    }

    @Test
    void countsAMissingTokenInsideTheWindowAsADisagreement() {
        NarrowingAudit audit = new NarrowingAudit();
        List<Token> narrowed = List.of(new Token(110, 5, "keyword"));
        List<Token> full = List.of(new Token(110, 5, "keyword"), new Token(150, 3, "number"));

        assertFalse(audit.record(narrowed, full, WINDOW));
        assertEquals(1, audit.disagreements());
        assertEquals(1, audit.tokensMissed());
    }

    @Test
    void countsAChangedTokenTypeAsADisagreement() {
        NarrowingAudit audit = new NarrowingAudit();

        // Same positions, different classification: the user sees the wrong colour, which is exactly
        // the failure narrowing could cause and the reason this is measured at all.
        assertFalse(
                audit.record(
                        List.of(new Token(110, 5, "variable")),
                        List.of(new Token(110, 5, "keyword")),
                        WINDOW));
        assertEquals(1, audit.disagreements());
    }

    @Test
    void reportsARateOverManyComparisons() {
        NarrowingAudit audit = new NarrowingAudit();
        List<Token> agreeing = List.of(new Token(110, 5, "keyword"));

        for (int i = 0; i < 9; i++) {
            audit.record(agreeing, agreeing, WINDOW);
        }
        audit.record(agreeing, List.of(new Token(110, 5, "variable")), WINDOW);

        assertEquals(0.1, audit.disagreementRate(), 1e-9);
    }

    @Test
    void reportsZeroRateBeforeAnythingIsCompared() {
        assertEquals(0.0, new NarrowingAudit().disagreementRate());
    }

    @Test
    void summaryNamesTheNumbersItReports() {
        NarrowingAudit audit = new NarrowingAudit();
        audit.record(List.of(), List.of(), WINDOW);

        assertTrue(audit.summary().contains("1 comparisons"));
    }
}
