package vega.core.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vega.core.document.Document;
import vega.core.document.Edit;
import vega.core.port.CancellationToken;
import vega.core.port.CancelledException;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;
import vega.core.port.SyntaxParserPort;

/**
 * Constitution Principle IV: cancellation must stop in-flight work, not merely discard its result.
 *
 * <p>A worker that keeps parsing a superseded version starves the pool exactly when typing is
 * fastest, which is the moment the budget matters most. So these tests assert what the service
 * stopped doing, not only what it returned.
 */
class HighlightServiceTest {

    /**
     * Long enough that the narrowed window still spans thousands of nodes. A short document makes
     * the window smaller than the walk under test, and the traversal finishes before it can be
     * cancelled — which says the narrowing works, not that cancellation does.
     */
    private static final String SOURCE = "class A { int x = 1; }".repeat(500);

    private static Document document() {
        return Document.opened("file:///A.java", SOURCE);
    }

    @Test
    void doesNotEvenStartParsingWhenAlreadyCancelled() {
        CountingParser parser = new CountingParser();
        HighlightService service = new HighlightService(parser);
        CancellationToken cancelled = CancellationToken.cancellable();
        cancelled.cancel();

        assertThrows(
                CancelledException.class,
                () ->
                        service.reanalyse(
                                new FakeTree(4), document(), Edit.replace(0, 0, "a", 1), SOURCE, cancelled));

        assertEquals(0, parser.reparses.get(), "a cancelled request must not reach the parser at all");
    }

    @Test
    void stopsBeforeHighlightingWhenCancelledDuringTheParse() {
        CountingParser parser = new CountingParser();
        CancellationToken token = CancellationToken.cancellable();
        parser.onReparse = token::cancel;
        HighlightService service = new HighlightService(parser);

        assertThrows(
                CancelledException.class,
                () -> service.reanalyse(new FakeTree(4), document(), Edit.replace(0, 0, "a", 1), SOURCE, token));
    }

    @Test
    void abandonsTheTraversalPartwayWhenCancelledDuringHighlighting() {
        CountingParser parser = new CountingParser();
        CancellationToken token = CancellationToken.cancellable();
        // A tree big enough that abandoning it is observable against the number of nodes walked.
        FakeTree tree = new FakeTree(5_000);
        tree.onNodeVisited = visited -> {
            if (visited == 100) {
                token.cancel();
            }
        };
        HighlightService service = new HighlightService(parser);
        parser.next = tree;

        assertThrows(
                CancelledException.class,
                () -> service.reanalyse(new FakeTree(1), document(), Edit.replace(0, 0, "a", 1), SOURCE, token));

        assertTrue(
                tree.visited.get() < 5_000,
                "traversal continued past cancellation: visited " + tree.visited.get() + " nodes");
    }

    @Test
    void producesAResultKeyedToTheDocumentVersionWhenNotCancelled() {
        CountingParser parser = new CountingParser();
        parser.next = new FakeTree(6);
        HighlightService service = new HighlightService(parser);
        Document before = document();
        Edit edit = Edit.replace(0, 0, "a", before.version());
        Document after = before.apply(edit);

        HighlightService.Reanalysis reanalysis =
                service.reanalyse(new FakeTree(6), after, edit, after.text(), CancellationToken.never());

        assertEquals(after.version(), reanalysis.result().documentVersion());
        assertTrue(reanalysis.result().appliesTo(after.version()));
    }

    @Test
    void narrowsTheRepaintToTheRegionAroundTheEdit() {
        CountingParser parser = new CountingParser();
        parser.next = new FakeTree(6);
        HighlightService service = new HighlightService(parser);
        Document before = document();
        Edit edit = Edit.replace(5, 5, "a", before.version());
        Document after = before.apply(edit);

        HighlightService.Reanalysis reanalysis =
                service.reanalyse(new FakeTree(6), after, edit, after.text(), CancellationToken.never());

        // Whatever the tree reports, the repainted window stays bounded by the edit position.
        assertTrue(reanalysis.window().start() <= 5 && reanalysis.window().end() >= 5);
    }

    private static final class CountingParser implements SyntaxParserPort {
        final AtomicInteger reparses = new AtomicInteger();
        Runnable onReparse = () -> {};
        ParsedTree next = new FakeTree(4);

        @Override
        public ParsedTree parse(CharSequence text, CancellationToken cancellation) {
            return next;
        }

        @Override
        public ParsedTree reparse(
                ParsedTree previous, Edit edit, CharSequence newText, CancellationToken cancellation) {
            reparses.incrementAndGet();
            onReparse.run();
            return next;
        }
    }

    /** A flat tree of identifier leaves, wide enough to make an abandoned walk measurable. */
    private static final class FakeTree implements ParsedTree {
        private final int nodeCount;
        final AtomicInteger visited = new AtomicInteger();
        java.util.function.IntConsumer onNodeVisited = value -> {};

        FakeTree(int nodeCount) {
            this.nodeCount = nodeCount;
        }

        @Override
        public boolean hasError() {
            return false;
        }

        @Override
        public List<ChangedRange> changedRangesSince(ParsedTree other) {
            return List.of(new ChangedRange(0, Math.max(1, nodeCount)));
        }

        @Override
        public SyntaxCursor cursor() {
            return new FakeCursor(this);
        }

        @Override
        public void close() {}
    }

    private static final class FakeCursor implements SyntaxCursor {
        private final FakeTree tree;
        private int index = -1;

        FakeCursor(FakeTree tree) {
            this.tree = tree;
        }

        @Override
        public boolean gotoFirstChild() {
            if (index >= 0) {
                return false;
            }
            index = 0;
            record();
            return true;
        }

        @Override
        public boolean gotoNextSibling() {
            if (index < 0 || index >= tree.nodeCount - 1) {
                return false;
            }
            index++;
            record();
            return true;
        }

        private void record() {
            tree.onNodeVisited.accept(tree.visited.incrementAndGet());
        }

        @Override
        public boolean gotoParent() {
            index = -1;
            return true;
        }

        @Override
        public String nodeType() {
            return index < 0 ? "program" : "identifier";
        }

        @Override
        public int startOffset() {
            return index < 0 ? 0 : index;
        }

        @Override
        public int endOffset() {
            return index < 0 ? Integer.MAX_VALUE : index + 1;
        }

        @Override
        public boolean isErrorNode() {
            return false;
        }

        @Override
        public void close() {}
    }
}
