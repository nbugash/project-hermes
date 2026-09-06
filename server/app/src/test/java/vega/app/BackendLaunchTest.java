package vega.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vega.lsp.VegaServerApi;
import vega.protocol.ProtocolVersion;
import vega.protocol.ReadDocumentParams;
import vega.protocol.ReadDocumentResult;

/**
 * Boots the real backend — real Dagger graph, real filesystem gateway, real tree-sitter adapter —
 * over a real JSON-RPC connection, and reads a real file through it.
 *
 * <p>Every other backend test substitutes something. This one substitutes only the transport
 * endpoints, so it is the test that fails if the assembled product does not work at all.
 */
class BackendLaunchTest {

    @TempDir Path tempDir;

    private ExecutorService executor;
    private VegaComponent component;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (component != null) {
            component.cpuWorkerPool().close();
        }
    }

    @Test
    void servesReadDocumentFromTheRealGraphOverStdioStyleStreams() throws Exception {
        Path file = tempDir.resolve("Real.java");
        Files.writeString(file, "class Real {\n    int x = 1;\n}\n", StandardCharsets.UTF_8);

        PipedInputStream clientReads = new PipedInputStream();
        PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
        PipedInputStream serverReads = new PipedInputStream();
        PipedOutputStream clientWrites = new PipedOutputStream(serverReads);

        executor = Executors.newFixedThreadPool(2);
        component = VegaComponent.create();
        Main.launch(component, serverReads, serverWrites, executor);

        Launcher<VegaServerApi> clientLauncher =
                new Launcher.Builder<VegaServerApi>()
                        .setLocalService(new SilentClient())
                        .setRemoteInterface(VegaServerApi.class)
                        .setInput(clientReads)
                        .setOutput(clientWrites)
                        .setExecutorService(executor)
                        .create();
        clientLauncher.startListening();
        VegaServerApi client = clientLauncher.getRemoteProxy();

        InitializeResult init = client.initialize(new InitializeParams()).get(15, TimeUnit.SECONDS);

        // lsp4j leaves `experimental` as raw JSON on the receiving side rather than a Map, because
        // its shape is server-defined. The TypeScript client has to unpack it the same way.
        com.google.gson.JsonObject experimental =
                (com.google.gson.JsonObject) init.getCapabilities().getExperimental();
        assertEquals(
                ProtocolVersion.CURRENT,
                experimental.get("vegaProtocolVersion").getAsString(),
                "the client must be able to refuse a protocol mismatch at initialize");

        ReadDocumentResult result =
                client.readDocument(new ReadDocumentParams(file.toUri().toString()))
                        .get(15, TimeUnit.SECONDS);

        assertEquals("class Real {\n    int x = 1;\n}\n", result.getText());
        assertEquals("UTF-8", result.getEncoding());
        assertNotNull(result.getContentHash());
        assertTrue(result.isHasTrailingNewline());
    }

    private static final class SilentClient implements LanguageClient {
        @Override
        public void telemetryEvent(Object object) {}

        @Override
        public void publishDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams diagnostics) {}

        @Override
        public void showMessage(org.eclipse.lsp4j.MessageParams messageParams) {}

        @Override
        public CompletableFuture<org.eclipse.lsp4j.MessageActionItem> showMessageRequest(
                org.eclipse.lsp4j.ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(org.eclipse.lsp4j.MessageParams message) {}
    }
}
