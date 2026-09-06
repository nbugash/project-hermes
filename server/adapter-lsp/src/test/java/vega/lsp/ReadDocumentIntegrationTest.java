package vega.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vega.core.document.DocumentMetadata;
import vega.core.document.DocumentService;
import vega.core.document.LineEnding;
import vega.core.port.FileGatewayPort;
import vega.protocol.ReadDocumentParams;
import vega.protocol.ReadDocumentResult;

/**
 * Drives the server over a real JSON-RPC connection rather than by calling handlers directly, so the
 * wire shapes in {@code protocol/schema/vega-extensions.json} are actually exercised. A handler test
 * that bypassed serialisation would pass while the client saw a malformed response.
 *
 * <p>Headless by construction: both ends run in this process over piped streams, with no editor.
 */
class ReadDocumentIntegrationTest {

    @TempDir Path tempDir;

    private ExecutorService executor;
    private VegaServerApi client;
    private CountingSyntaxParser parser;
    private Path file;

    @BeforeEach
    void startServer() throws Exception {
        file = tempDir.resolve("Sample.java");
        Files.writeString(file, "class Sample {\n    int x = 1;\n}\n", StandardCharsets.UTF_8);

        parser = new CountingSyntaxParser();
        TempFileGateway gateway = new TempFileGateway();
        DocumentService documents = new DocumentService(gateway);
        VegaDocumentHandlers handlers = new VegaDocumentHandlers(documents, gateway);
        VegaLanguageServer[] holder = new VegaLanguageServer[1];
        VegaTextDocumentService textDocuments =
                new VegaTextDocumentService(
                        documents,
                        parser,
                        new SemanticTokensHandler(VegaLanguageServer.TokenLegend.TYPES),
                        new ProgressReporter(() -> holder[0] == null ? null : holder[0].connectedClient()),
                        new vega.core.highlight.HighlightService(parser));
        VegaLanguageServer server =
                new VegaLanguageServer(textDocuments, new NoOpWorkspaceService(), handlers);
        holder[0] = server;

        PipedInputStream clientReads = new PipedInputStream();
        PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
        PipedInputStream serverReads = new PipedInputStream();
        PipedOutputStream clientWrites = new PipedOutputStream(serverReads);

        executor = Executors.newFixedThreadPool(2);
        Launcher<LanguageClient> serverLauncher =
                LSPLauncher.createServerLauncher(server, serverReads, serverWrites, executor, w -> w);
        serverLauncher.startListening();

        Launcher<VegaServerApi> clientLauncher =
                new Launcher.Builder<VegaServerApi>()
                        .setLocalService(new NoOpLanguageClient())
                        .setRemoteInterface(VegaServerApi.class)
                        .setInput(clientReads)
                        .setOutput(clientWrites)
                        .setExecutorService(executor)
                        .create();
        clientLauncher.startListening();
        client = clientLauncher.getRemoteProxy();

        client.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stopServer() {
        executor.shutdownNow();
    }

    @Test
    void readDocumentReturnsTextAndTheMetadataNeededToSaveItBack() throws Exception {
        ReadDocumentResult result =
                client.readDocument(new ReadDocumentParams(file.toUri().toString()))
                        .get(10, TimeUnit.SECONDS);

        assertEquals("class Sample {\n    int x = 1;\n}\n", result.getText());
        assertEquals("UTF-8", result.getEncoding());
        assertEquals("LF", result.getLineEnding());
        assertTrue(result.isHasTrailingNewline());
        assertFalse(result.getContentHash().isBlank(), "content hash is required to detect disk change");
    }

    @Test
    void readDocumentDoesNotParseAsASideEffect() throws Exception {
        client.readDocument(new ReadDocumentParams(file.toUri().toString())).get(10, TimeUnit.SECONDS);

        // Reading is not opening. Parsing here would put whole-file work on the cold-start path
        // before the editor has even asked for highlighting, which is the cost FR-002 and the
        // cold-start budget exist to avoid.
        assertEquals(0, parser.parseCount.get(), "vega/readDocument must not trigger a parse");
    }

    @Test
    void didOpenIsWhatTriggersParsing() throws Exception {
        String uri = file.toUri().toString();
        client.readDocument(new ReadDocumentParams(uri)).get(10, TimeUnit.SECONDS);

        client.getTextDocumentService()
                .didOpen(
                        new DidOpenTextDocumentParams(
                                new TextDocumentItem(uri, "java", 1, Files.readString(file))));

        // The notification is one-way, so wait for the effect rather than for a reply.
        for (int i = 0; i < 100 && parser.parseCount.get() == 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(1, parser.parseCount.get(), "didOpen should parse exactly once");
    }

    @Test
    void semanticTokensRangeIsRoutedOverTheConnection() throws Exception {
        String uri = file.toUri().toString();
        client.readDocument(new ReadDocumentParams(uri)).get(10, TimeUnit.SECONDS);
        client.getTextDocumentService()
                .didOpen(
                        new DidOpenTextDocumentParams(
                                new TextDocumentItem(uri, "java", 1, Files.readString(file))));
        for (int i = 0; i < 100 && parser.parseCount.get() == 0; i++) {
            Thread.sleep(20);
        }

        org.eclipse.lsp4j.SemanticTokensRangeParams params =
                new org.eclipse.lsp4j.SemanticTokensRangeParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(uri));
        params.setRange(
                new org.eclipse.lsp4j.Range(
                        new org.eclipse.lsp4j.Position(0, 0), new org.eclipse.lsp4j.Position(1, 0)));

        // A handler that exists but is not registered fails here and nowhere else.
        org.eclipse.lsp4j.SemanticTokens tokens =
                client.getTextDocumentService().semanticTokensRange(params).get(10, TimeUnit.SECONDS);
        assertTrue(tokens.getData().isEmpty(), "stub tree yields no tokens, but the call must succeed");
    }

    /** Reads straight from the temp directory; the real adapter is exercised by its own tests. */
    private final class TempFileGateway implements FileGatewayPort {
        @Override
        public FileContent read(String uri) {
            try {
                Path path = Path.of(java.net.URI.create(uri));
                String text = Files.readString(path, StandardCharsets.UTF_8);
                return new FileContent(
                        text,
                        new DocumentMetadata(
                                StandardCharsets.UTF_8, LineEnding.LF, text.endsWith("\n"), "hash-" + text.length()));
            } catch (Exception e) {
                throw new IllegalStateException("read failed for " + uri, e);
            }
        }

        @Override
        public WriteOutcome write(
                String uri, String text, DocumentMetadata metadata, String expectedDiskHash) {
            return WriteOutcome.FAILED;
        }
    }

    private static final class CountingSyntaxParser implements vega.core.port.SyntaxParserPort {
        final AtomicInteger parseCount = new AtomicInteger();

        @Override
        public vega.core.port.ParsedTree parse(
                CharSequence text, vega.core.port.CancellationToken cancellation) {
            parseCount.incrementAndGet();
            return new StubTree();
        }

        @Override
        public vega.core.port.ParsedTree reparse(
                vega.core.port.ParsedTree previous,
                vega.core.document.Edit edit,
                CharSequence newText,
                vega.core.port.CancellationToken cancellation) {
            parseCount.incrementAndGet();
            return new StubTree();
        }
    }

    private static final class StubTree implements vega.core.port.ParsedTree {
        @Override
        public boolean hasError() {
            return false;
        }

        @Override
        public java.util.List<vega.core.highlight.ChangedRange> changedRangesSince(
                vega.core.port.ParsedTree other) {
            return java.util.List.of();
        }

        @Override
        public vega.core.port.SyntaxCursor cursor() {
            return new EmptyCursor();
        }

        @Override
        public void close() {}
    }

    /** Walks nothing: enough to prove a range request is routed and answered. */
    private static final class EmptyCursor implements vega.core.port.SyntaxCursor {
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

    private static final class NoOpWorkspaceService
            implements org.eclipse.lsp4j.services.WorkspaceService {
        @Override
        public void didChangeConfiguration(
                org.eclipse.lsp4j.DidChangeConfigurationParams params) {}

        @Override
        public void didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}
    }

    private static final class NoOpLanguageClient implements LanguageClient {
        @Override
        public void telemetryEvent(Object object) {}

        @Override
        public void publishDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams diagnostics) {}

        @Override
        public void showMessage(org.eclipse.lsp4j.MessageParams messageParams) {}

        @Override
        public java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem>
                showMessageRequest(org.eclipse.lsp4j.ShowMessageRequestParams requestParams) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(org.eclipse.lsp4j.MessageParams message) {}
    }
}
