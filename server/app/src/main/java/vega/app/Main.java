package vega.app;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import vega.core.obs.Correlation;
import vega.lsp.VegaLanguageServer;
import vega.protocol.ProtocolVersion;

/**
 * Backend entry point.
 *
 * <p>Speaks LSP over stdio, so nothing may be written to {@code System.out} except protocol
 * traffic — diagnostics go to stderr. A stray {@code println} anywhere in the backend corrupts the
 * message stream and presents to the user as an editor that connects and then goes silent.
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {}

    public static void main(String[] args) {
        try (var scope = Correlation.beginScope(Correlation.newId())) {
            VegaComponent component = VegaComponent.create();
            LOG.info(
                    () ->
                            "vega backend starting, protocol "
                                    + ProtocolVersion.CURRENT
                                    + ", correlation "
                                    + Correlation.current());

            // Resolved before the hook is registered, not inside it. Dagger builds lazily, so a hook
            // that calls into the graph constructs Vert.x during JVM shutdown — which then fails to
            // register its own shutdown hook and dies with "Shutdown in progress".
            CpuWorkerPool workerPool = component.cpuWorkerPool();
            Runtime.getRuntime().addShutdownHook(new Thread(workerPool::close, "vega-shutdown"));

            // launch() already starts listening; calling it again would put two reader threads on
            // the same stream, each consuming half the messages.
            launch(component, System.in, System.out, null);
        }
    }

    /**
     * Connects the assembled server to a pair of streams.
     *
     * <p>Split out from {@code main} so the whole graph can be exercised over a real connection in a
     * test without spawning a process; stdio is just the pair of streams the editor supplies.
     *
     * @param executor thread pool for inbound messages, or null to let lsp4j supply its own
     */
    public static Launcher<LanguageClient> launch(
            VegaComponent component, InputStream in, OutputStream out, ExecutorService executor) {
        VegaLanguageServer server = component.languageServer();
        Launcher<LanguageClient> launcher =
                executor == null
                        ? LSPLauncher.createServerLauncher(server, in, out)
                        : LSPLauncher.createServerLauncher(server, in, out, executor, w -> w);

        // lsp4j hands the client to the server here; the progress reporter reads it from there, so
        // connecting before listening is what makes $/progress reach the editor at all.
        server.connect(launcher.getRemoteProxy());
        launcher.startListening();
        return launcher;
    }
}
