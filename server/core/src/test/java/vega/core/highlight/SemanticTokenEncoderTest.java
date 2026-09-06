package vega.core.highlight;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LSP encodes semantic tokens as five integers per token, all relative to the previous token.
 *
 * <p>That relativity is why encoding errors are catastrophic rather than local: a single wrong
 * delta shifts every subsequent token in the document. These tests pin the arithmetic.
 */
class SemanticTokenEncoderTest {

    private static final List<String> LEGEND = List.of("keyword", "type", "variable");

    @Test
    void encodesASingleTokenAbsolutelyFromTheOrigin() {
        String text = "class A";

        int[] encoded = SemanticTokenEncoder.encode(text, List.of(new Token(0, 5, "keyword")), LEGEND);

        // deltaLine, deltaStart, length, typeIndex, modifiers
        assertArrayEquals(new int[] {0, 0, 5, 0, 0}, encoded);
    }

    @Test
    void encodesTheSecondTokenOnTheSameLineRelativeToTheFirst() {
        String text = "class Alpha";

        int[] encoded =
                SemanticTokenEncoder.encode(
                        text, List.of(new Token(0, 5, "keyword"), new Token(6, 5, "type")), LEGEND);

        assertArrayEquals(new int[] {0, 0, 5, 0, 0, 0, 6, 5, 1, 0}, encoded);
    }

    @Test
    void resetsTheColumnDeltaOnANewLine() {
        String text = "class A\n  int x";

        int[] encoded =
                SemanticTokenEncoder.encode(
                        text, List.of(new Token(0, 5, "keyword"), new Token(10, 3, "type")), LEGEND);

        // Second token is one line down at column 2; deltaStart is absolute within a new line.
        assertArrayEquals(new int[] {0, 0, 5, 0, 0, 1, 2, 3, 1, 0}, encoded);
    }

    @Test
    void skipsTokensWhoseTypeIsNotInTheLegend() {
        // The legend is the wire contract. Emitting an index outside it would be interpreted as a
        // different type by the client, so an unknown type is dropped rather than guessed.
        String text = "class A";

        int[] encoded =
                SemanticTokenEncoder.encode(
                        text, List.of(new Token(0, 5, "keyword"), new Token(6, 1, "not-in-legend")), LEGEND);

        assertArrayEquals(new int[] {0, 0, 5, 0, 0}, encoded);
    }

    @Test
    void encodesNothingForNoTokens() {
        assertEquals(0, SemanticTokenEncoder.encode("class A", List.of(), LEGEND).length);
    }

    @Test
    void countsLinesAcrossMultipleNewlinesBetweenTokens() {
        String text = "a\n\n\nb";

        int[] encoded =
                SemanticTokenEncoder.encode(
                        text, List.of(new Token(0, 1, "variable"), new Token(4, 1, "variable")), LEGEND);

        assertArrayEquals(new int[] {0, 0, 1, 2, 0, 3, 0, 1, 2, 0}, encoded);
    }
}
