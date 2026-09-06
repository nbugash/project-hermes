package vega.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vega.core.document.Document;
import vega.core.document.Edit;
import vega.core.port.CancellationToken;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;

/**
 * Offsets crossing the port must be UTF-16 code units, because that is what the core's {@link
 * Document} and LSP both use. tree-sitter counts UTF-8 bytes internally, so every offset it reports
 * has to be translated on the way out and every offset handed to it translated on the way in.
 *
 * <p>The assertions are deliberately expressed as {@code source.substring(start, end)}: that is
 * exactly what the core does with these offsets, so if the unit is wrong the test fails the same way
 * production would — with the wrong span of text, not an exception.
 */
class NonAsciiOffsetTest {

    private static TreeSitterSyntaxAdapter adapter;

    @BeforeAll
    static void setUp() {
        adapter = new TreeSitterSyntaxAdapter();
    }

    @AfterAll
    static void tearDown() {
        adapter.close();
    }

    /** Depth-first search for the first node of a type whose span matches the wanted text. */
    private static int[] findNode(SyntaxCursor cursor, String type, String source, String wanted) {
        List<int[]> found = new ArrayList<>();
        walk(cursor, type, source, wanted, found);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void walk(
            SyntaxCursor cursor, String type, String source, String wanted, List<int[]> found) {
        if (!found.isEmpty()) {
            return;
        }
        if (cursor.nodeType().equals(type)) {
            int start = cursor.startOffset();
            int end = cursor.endOffset();
            if (start >= 0 && end <= source.length() && source.substring(start, end).equals(wanted)) {
                found.add(new int[] {start, end});
                return;
            }
        }
        if (cursor.gotoFirstChild()) {
            do {
                walk(cursor, type, source, wanted, found);
            } while (found.isEmpty() && cursor.gotoNextSibling());
            cursor.gotoParent();
        }
    }

    @Test
    void identifierAfterATwoByteCharacterReportsUtf16Offsets() {
        String source = "class A { String s = \"café\"; int after = 1; }";

        try (ParsedTree tree = adapter.parse(source, CancellationToken.never());
                SyntaxCursor cursor = tree.cursor()) {
            int[] span = findNode(cursor, "identifier", source, "after");

            assertNotNull(span, "identifier 'after' not found at UTF-16 offsets");
            assertEquals("after", source.substring(span[0], span[1]));
        }
    }

    @Test
    void identifierAfterAnAstralCharacterReportsUtf16Offsets() {
        // The emoji is four UTF-8 bytes but two UTF-16 units, so byte and UTF-16 offsets differ by
        // two from here on — enough to slice an identifier apart if the unit is wrong.
        String source = "class A {\n  // 😀 note\n  int after = 1;\n}\n";

        try (ParsedTree tree = adapter.parse(source, CancellationToken.never());
                SyntaxCursor cursor = tree.cursor()) {
            int[] span = findNode(cursor, "identifier", source, "after");

            assertNotNull(span, "identifier 'after' not found at UTF-16 offsets");
            assertEquals("after", source.substring(span[0], span[1]));
        }
    }

    @Test
    void reparseAppliesEditsAtUtf16OffsetsMatchingTheCoreDocument() {
        // The core applies the edit to its String; the adapter applies the same Edit to its bytes.
        // If the two disagree about what the offsets mean, the mirrors diverge and every later
        // parse is of text the user never typed.
        String source = "class A { String s = \"café\"; int after = 1; }";
        Document document = Document.opened("file:///A.java", source);

        int editOffset = source.indexOf("after");
        Edit edit = Edit.replace(editOffset, editOffset, "renamed_", document.version());
        Document edited = document.apply(edit);

        try (ParsedTree original = adapter.parse(source, CancellationToken.never());
                ParsedTree reparsed =
                        adapter.reparse(original, edit, edited.text(), CancellationToken.never());
                SyntaxCursor cursor = reparsed.cursor()) {

            String expected = edited.text();
            int[] span = findNode(cursor, "identifier", expected, "renamed_after");

            assertNotNull(span, "renamed identifier not found; adapter and core disagree on offsets");
            assertEquals("renamed_after", expected.substring(span[0], span[1]));
        }
    }
}
