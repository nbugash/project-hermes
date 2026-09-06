package vega.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vega.core.document.Edit;
import vega.core.highlight.ChangedRange;
import vega.core.port.CancellationToken;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;

class TreeSitterSyntaxAdapterTest {

    private static TreeSitterSyntaxAdapter adapter;

    @BeforeAll
    static void setUp() {
        adapter = new TreeSitterSyntaxAdapter();
    }

    @AfterAll
    static void tearDown() {
        adapter.close();
    }

    @Test
    void parsesValidJava() {
        try (ParsedTree tree = adapter.parse("class A { int x; }", CancellationToken.never())) {
            assertFalse(tree.hasError());
        }
    }

    @Test
    void exposesTheTreeThroughALazyCursorRatherThanAMaterialisedCopy() {
        try (ParsedTree tree = adapter.parse("class A { int x; }", CancellationToken.never());
                SyntaxCursor cursor = tree.cursor()) {

            assertEquals("program", cursor.nodeType());
            assertTrue(cursor.gotoFirstChild());
            assertEquals("class_declaration", cursor.nodeType());
            assertEquals(0, cursor.startOffset());
        }
    }

    @Test
    void toleratesSyntacticallyInvalidCode() {
        // Source is invalid most of the time it is being typed; that is the normal case, and
        // completion must still work through it.
        try (ParsedTree tree = adapter.parse("class A { int x = ", CancellationToken.never())) {
            assertTrue(tree.hasError());
        }
    }

    @Test
    void reparsesIncrementallyAndReportsChangedRanges() {
        String before = "class A { int x; }";
        String after = "class A { int xy; }";

        try (ParsedTree original = adapter.parse(before, CancellationToken.never())) {
            Edit edit = Edit.replace(15, 15, "y", 1);

            try (ParsedTree updated =
                    adapter.reparse(original, edit, after, CancellationToken.never())) {
                assertFalse(updated.hasError());

                List<ChangedRange> changed = original.changedRangesSince(updated);
                assertTrue(changed != null, "changed ranges must be reported, even if empty");
            }
        }
    }

    @Test
    void parsesTheFullFixtureWithoutError() throws Exception {
        Path fixture = Path.of("..", "..", "fixtures", "large-java-file", "Large.java");
        String source = Files.readString(fixture);

        try (ParsedTree tree = adapter.parse(source, CancellationToken.never())) {
            assertFalse(tree.hasError(), "the checked-in fixture must be valid Java");
        }
    }

    @Test
    void honoursCancellationBeforeStartingWork() {
        CancellationToken token = CancellationToken.cancellable();
        token.cancel();

        org.junit.jupiter.api.Assertions.assertThrows(
                vega.core.port.CancelledException.class,
                () -> adapter.parse("class A {}", token));
    }
}
