package vega.lsp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.Test;
import vega.core.document.DocumentMetadata;
import vega.core.document.DocumentService;
import vega.core.document.LineEnding;
import vega.core.highlight.ChangedRange;
import vega.core.highlight.HighlightService;
import vega.core.port.CancellationToken;
import vega.core.port.FileGatewayPort;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;
import vega.core.port.SyntaxParserPort;

/**
 * Constitution Principle IV: {@code $/cancelRequest} must free the worker, not merely discard the
 * reply.
 *
 * <p>A cancelled request whose worker keeps walking the tree still occupies a pool thread. During
 * fast typing that is exactly when the next request arrives, so a server that only drops replies
 * degrades under precisely the load cancellation exists to handle. The assertion is therefore about
 * the traversal stopping, not about the future completing.
 */
class CancellationFreesWorkerTest {

    private static final String URI = "file:///A.java";
    private static final String SOURCE = "class A { int x = 1; }".repeat(2_000);

    /**
     * Fewer nodes than the document has characters, so a complete walk visits every one of them.
     * With more, the walk would stop at the window edge and the assertion below would pass whether
     * or not cancellation works.
     */
    private static final int NODE_COUNT = 40_000;

    @Test
    void cancellingTheRequestStopsTheTraversalInFlight() throws Exception {
        CountingTree tree = new CountingTree(NODE_COUNT);
        CountDownLatch started = new CountDownLatch(1);
        tree.onNodeVisited = visited -> {
            if (visited == 50) {
                started.countDown();
            }
        };

        VegaTextDocumentService service = serviceFor(tree);
        service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, SOURCE)));

        CompletableFuture<SemanticTokens> request =
                service.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(URI)));

        assertTrue(started.await(10, TimeUnit.SECONDS), "the walk never started");
        request.cancel(true);

        // Give the worker a moment to notice; then confirm it stopped well short of the whole tree.
        for (int i = 0; i < 100 && tree.visited.get() < NODE_COUNT; i++) {
            int before = tree.visited.get();
            Thread.sleep(20);
            if (tree.visited.get() == before) {
                break;
            }
        }

        // Well short of the whole tree, not merely one node short of it: the point is that the
        // worker was released, and a walk that stops at 39,999 has occupied the thread throughout.
        assertTrue(
                tree.visited.get() < NODE_COUNT / 4,
                "traversal continued after cancellation: " + tree.visited.get() + " of " + NODE_COUNT);
    }

    private static VegaTextDocumentService serviceFor(ParsedTree tree) {
        SyntaxParserPort parser =
                new SyntaxParserPort() {
                    @Override
                    public ParsedTree parse(CharSequence text, CancellationToken cancellation) {
                        return tree;
                    }

                    @Override
                    public ParsedTree reparse(
                            ParsedTree previous,
                            vega.core.document.Edit edit,
                            CharSequence newText,
                            CancellationToken cancellation) {
                        return tree;
                    }
                };
        DocumentService documents = new DocumentService(new FixedGateway(SOURCE));
        return new VegaTextDocumentService(
                documents,
                parser,
                new SemanticTokensHandler(VegaLanguageServer.TokenLegend.TYPES),
                new ProgressReporter(() -> null),
                new HighlightService(parser));
    }

    private static final class CountingTree implements ParsedTree {
        private final int nodeCount;
        final AtomicInteger visited = new AtomicInteger();
        java.util.function.IntConsumer onNodeVisited = value -> {};

        CountingTree(int nodeCount) {
            this.nodeCount = nodeCount;
        }

        @Override
        public boolean hasError() {
            return false;
        }

        @Override
        public List<ChangedRange> changedRangesSince(ParsedTree other) {
            return List.of();
        }

        @Override
        public SyntaxCursor cursor() {
            return new CountingCursor(this);
        }

        @Override
        public void close() {}
    }

    private static final class CountingCursor implements SyntaxCursor {
        private final CountingTree tree;
        private int index = -1;

        CountingCursor(CountingTree tree) {
            this.tree = tree;
        }

        @Override
        public boolean gotoFirstChild() {
            if (index >= 0) {
                return false;
            }
            index = 0;
            tree.onNodeVisited.accept(tree.visited.incrementAndGet());
            return true;
        }

        @Override
        public boolean gotoNextSibling() {
            if (index < 0 || index >= tree.nodeCount - 1) {
                return false;
            }
            index++;
            tree.onNodeVisited.accept(tree.visited.incrementAndGet());
            // Slow the walk enough that cancellation has a window to be observed.
            if (index % 500 == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
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

    private record FixedGateway(String text) implements FileGatewayPort {
        @Override
        public FileContent read(String uri) {
            return new FileContent(
                    text, new DocumentMetadata(StandardCharsets.UTF_8, LineEnding.LF, false, "hash"));
        }

        @Override
        public WriteOutcome write(
                String uri, String content, DocumentMetadata metadata, String expectedDiskHash) {
            return WriteOutcome.FAILED;
        }
    }
}
