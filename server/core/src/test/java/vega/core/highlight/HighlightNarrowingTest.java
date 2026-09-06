package vega.core.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * tree-sitter's reported changed range is not a repaint region.
 *
 * <p>Measurement (research D12) showed it degenerates to the whole document on any brace edit,
 * because node ancestry genuinely changes even when every token's appearance is identical. Driving a
 * repaint from it directly repaints the entire file the first time a developer types '{'. Narrowing
 * to a bounded window around the edit is sound because a user cannot type outside their viewport.
 */
class HighlightNarrowingTest {

    private static final int DOCUMENT_LENGTH = 100_000;

    @Test
    void clipsAWholeFileReportedRangeToTheWindowAroundTheEdit() {
        ChangedRange narrowed =
                HighlightNarrowing.narrow(
                        List.of(new ChangedRange(0, DOCUMENT_LENGTH)), 50_000, DOCUMENT_LENGTH, 1_000);

        assertEquals(49_000, narrowed.start());
        assertEquals(51_000, narrowed.end());
    }

    @Test
    void keepsAReportedRangeThatIsAlreadySmallerThanTheWindow() {
        ChangedRange narrowed =
                HighlightNarrowing.narrow(
                        List.of(new ChangedRange(49_900, 50_100)), 50_000, DOCUMENT_LENGTH, 1_000);

        assertEquals(49_900, narrowed.start());
        assertEquals(50_100, narrowed.end());
    }

    @Test
    void clampsTheWindowToTheStartOfTheDocument() {
        ChangedRange narrowed =
                HighlightNarrowing.narrow(List.of(new ChangedRange(0, DOCUMENT_LENGTH)), 10, DOCUMENT_LENGTH, 1_000);

        assertEquals(0, narrowed.start());
        assertTrue(narrowed.end() <= DOCUMENT_LENGTH);
    }

    @Test
    void clampsTheWindowToTheEndOfTheDocument() {
        ChangedRange narrowed =
                HighlightNarrowing.narrow(
                        List.of(new ChangedRange(0, DOCUMENT_LENGTH)), DOCUMENT_LENGTH - 10, DOCUMENT_LENGTH, 1_000);

        assertEquals(DOCUMENT_LENGTH, narrowed.end());
        assertTrue(narrowed.start() >= 0);
    }

    @Test
    void spansEveryReportedRangeThatFallsInsideTheWindow() {
        ChangedRange narrowed =
                HighlightNarrowing.narrow(
                        List.of(new ChangedRange(49_500, 49_600), new ChangedRange(50_400, 50_500)),
                        50_000,
                        DOCUMENT_LENGTH,
                        1_000);

        // One span covering both is correct and cheap: the gap between them is small enough that
        // painting it costs less than tracking two regions through the token pipeline.
        assertEquals(49_500, narrowed.start());
        assertEquals(50_500, narrowed.end());
    }

    @Test
    void ignoresReportedRangesEntirelyOutsideTheWindow() {
        ChangedRange narrowed =
                HighlightNarrowing.narrow(
                        List.of(new ChangedRange(0, 100), new ChangedRange(49_900, 50_100)),
                        50_000,
                        DOCUMENT_LENGTH,
                        1_000);

        assertEquals(49_900, narrowed.start());
        assertEquals(50_100, narrowed.end());
    }

    @Test
    void fallsBackToTheWindowWhenNothingIsReported() {
        // The edit changed the text even if the tree's shape survived, so the window is still the
        // right thing to repaint; returning nothing would leave the typed character uncoloured.
        ChangedRange narrowed = HighlightNarrowing.narrow(List.of(), 50_000, DOCUMENT_LENGTH, 1_000);

        assertEquals(49_000, narrowed.start());
        assertEquals(51_000, narrowed.end());
    }

    @Test
    void theWindowStaysBoundedNoMatterHowLargeTheDocument() {
        // T074. A brace edit makes the syntax layer report the whole file as changed, so the
        // reported range grows with the document while the repaint must not. If this ever scaled
        // with document size, a single '{' in a large file would cost a whole-file repaint — the
        // exact failure narrowing exists to prevent, and one that only appears on large files.
        for (int documentLength : new int[] {10_000, 1_000_000, 10_000_000}) {
            ChangedRange narrowed =
                    HighlightNarrowing.narrow(
                            List.of(new ChangedRange(0, documentLength)),
                            documentLength / 2,
                            documentLength,
                            1_000);

            assertEquals(2_000, narrowed.length(), "window grew with document length " + documentLength);
        }
    }

    @Test
    void boundsDamageWhenSeveralDegenerateRangesAreReported() {
        // Error recovery can report several ranges, each spanning most of the file.
        ChangedRange narrowed =
                HighlightNarrowing.narrow(
                        List.of(
                                new ChangedRange(0, DOCUMENT_LENGTH),
                                new ChangedRange(0, DOCUMENT_LENGTH / 2),
                                new ChangedRange(DOCUMENT_LENGTH / 2, DOCUMENT_LENGTH)),
                        50_000,
                        DOCUMENT_LENGTH,
                        1_000);

        assertTrue(narrowed.length() <= 2_000, "several degenerate ranges escaped the window");
    }

    @Test
    void handlesAnEmptyDocument() {
        ChangedRange narrowed = HighlightNarrowing.narrow(List.of(), 0, 0, 1_000);

        assertTrue(narrowed.isEmpty());
    }
}
