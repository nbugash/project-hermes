package vega.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.Test;
import vega.core.document.DocumentMetadata;
import vega.core.document.DocumentService;
import vega.core.document.LineEnding;
import vega.core.highlight.HighlightService;
import vega.core.obs.Correlation;
import vega.core.port.CancellationToken;
import vega.core.port.FileGatewayPort;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;
import vega.core.port.SyntaxParserPort;

/**
 * Constitution Principle IX: one request's correlation id must appear on every line it produces,
 * including work that ran on a different thread.
 *
 * <p>A plain {@link ThreadLocal} satisfies this until the moment work is handed to the worker pool,
 * at which point the id silently disappears — and the logs still look plausible, just unjoinable.
 * That is precisely the case worth testing, because nothing fails when it breaks.
 */
class CorrelationPropagationTest {

    private static final String URI = "file:///A.java";

    @Test
    void anIdBoundOnOneThreadReachesWorkRunOnAnother() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            String id = Correlation.newId();
            String observed;
            try (var scope = Correlation.beginScope(id)) {
                observed = pool.submit(Correlation.propagating(Correlation::current)).get(5, TimeUnit.SECONDS);
            }

            assertEquals(id, observed, "the id did not survive dispatch to the worker pool");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theWorkerThreadIsLeftCleanForTheNextRequest() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            try (var scope = Correlation.beginScope(Correlation.newId())) {
                pool.submit(Correlation.propagating(Correlation::current)).get(5, TimeUnit.SECONDS);
            }

            // Pools reuse threads. A leaked id would attach this request's identity to the next
            // one's log lines — worse than no id at all, because it is confidently wrong.
            assertEquals(null, pool.submit(Correlation::current).get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eachRequestThroughTheServiceGetsItsOwnId() throws Exception {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        StubParser parser = new StubParser(ids);
        DocumentService documents = new DocumentService(new FixedGateway());
        VegaTextDocumentService service =
                new VegaTextDocumentService(
                        documents,
                        parser,
                        new SemanticTokensHandler(VegaLanguageServer.TokenLegend.TYPES),
                        new ProgressReporter(() -> null),
                        new HighlightService(parser));

        service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(URI, "java", 1, "class A {}")));
        service.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(URI))).get();

        assertTrue(ids.size() >= 1, "no correlation id was bound while handling requests");
        for (String id : ids) {
            assertNotNull(id, "a request ran with no correlation id bound");
        }
    }

    @Test
    void twoSeparateRequestsDoNotShareAnId() {
        String first;
        String second;
        try (var scope = Correlation.beginScope(Correlation.newId())) {
            first = Correlation.current();
        }
        try (var scope = Correlation.beginScope(Correlation.newId())) {
            second = Correlation.current();
        }

        assertNotEquals(first, second);
    }

    /** Records the correlation id in force whenever the parser is called. */
    private record StubParser(Set<String> ids) implements SyntaxParserPort {
        @Override
        public ParsedTree parse(CharSequence text, CancellationToken cancellation) {
            if (Correlation.current() != null) {
                ids.add(Correlation.current());
            }
            return new StubTree();
        }

        @Override
        public ParsedTree reparse(
                ParsedTree previous,
                vega.core.document.Edit edit,
                CharSequence newText,
                CancellationToken cancellation) {
            return new StubTree();
        }
    }

    private static final class StubTree implements ParsedTree {
        @Override
        public boolean hasError() {
            return false;
        }

        @Override
        public java.util.List<vega.core.highlight.ChangedRange> changedRangesSince(ParsedTree other) {
            return java.util.List.of();
        }

        @Override
        public SyntaxCursor cursor() {
            return new EmptyCursor();
        }

        @Override
        public void close() {}
    }

    private static final class EmptyCursor implements SyntaxCursor {
        @Override
        public boolean gotoFirstChild() {
            return false;
        }

        @Override
        public boolean gotoNextSibling() {
            return false;
        }

        @Override
        public boolean gotoParent() {
            return false;
        }

        @Override
        public String nodeType() {
            return "program";
        }

        @Override
        public int startOffset() {
            return 0;
        }

        @Override
        public int endOffset() {
            return 0;
        }

        @Override
        public boolean isErrorNode() {
            return false;
        }

        @Override
        public void close() {}
    }

    private record FixedGateway() implements FileGatewayPort {
        @Override
        public FileContent read(String uri) {
            return new FileContent(
                    "class A {}",
                    new DocumentMetadata(StandardCharsets.UTF_8, LineEnding.LF, false, "hash"));
        }

        @Override
        public WriteOutcome write(
                String uri, String text, DocumentMetadata metadata, String expectedDiskHash) {
            return WriteOutcome.FAILED;
        }
    }
}
