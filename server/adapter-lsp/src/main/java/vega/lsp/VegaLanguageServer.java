package vega.lsp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.PositionEncodingKind;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import vega.core.obs.Correlation;
import vega.protocol.ProtocolVersion;

/**
 * Inbound adapter. Translates protocol messages into calls on core ports and contains no analysis
 * logic of its own (Constitution Principle III).
 */
public class VegaLanguageServer implements VegaServerApi, org.eclipse.lsp4j.services.LanguageClientAware {

    private final TextDocumentService textDocumentService;
    private final WorkspaceService workspaceService;
    private final VegaDocumentHandlers documentHandlers;
    private volatile org.eclipse.lsp4j.services.LanguageClient connectedClient;

    public VegaLanguageServer(
            TextDocumentService textDocumentService,
            WorkspaceService workspaceService,
            VegaDocumentHandlers documentHandlers) {
        this.textDocumentService = textDocumentService;
        this.workspaceService = workspaceService;
        this.documentHandlers = documentHandlers;
    }

    @Override
    public CompletableFuture<vega.protocol.ReadDocumentResult> readDocument(
            vega.protocol.ReadDocumentParams params) {
        return documentHandlers.readDocument(params);
    }

    @Override
    public CompletableFuture<vega.protocol.SaveDocumentResult> saveDocument(
            vega.protocol.SaveDocumentParams params) {
        return documentHandlers.saveDocument(params);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        try (var scope = Correlation.beginScope(Correlation.newId())) {
            ServerCapabilities capabilities = new ServerCapabilities();

            // Incremental sync is mandatory: full-document synchronisation per edit is the
            // whole-file re-analysis Constitution Principle VI rejects.
            capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);

            // Position encoding is negotiated explicitly. Semantic token offsets are relative, so a
            // client/server disagreement here does not misplace one token — it shifts every
            // subsequent token in the document.
            capabilities.setPositionEncoding(PositionEncodingKind.UTF16);

            SemanticTokensWithRegistrationOptions semanticTokens =
                    new SemanticTokensWithRegistrationOptions(
                            new SemanticTokensLegend(TokenLegend.TYPES, TokenLegend.MODIFIERS));
            semanticTokens.setFull(new org.eclipse.lsp4j.SemanticTokensServerFull(true));
            semanticTokens.setRange(true);
            capabilities.setSemanticTokensProvider(semanticTokens);

            // Echoed so the client can refuse to start on a mismatch rather than discovering it
            // later as a malformed response. The vega/* surface is an experimental capability by
            // LSP's own taxonomy, which is exactly where a custom extension belongs.
            capabilities.setExperimental(Map.of("vegaProtocolVersion", ProtocolVersion.CURRENT));

            return CompletableFuture.completedFuture(new InitializeResult(capabilities));
        }
    }

    /**
     * lsp4j supplies the client after construction, so the reporter is handed it here rather than
     * taking it as a constructor argument it could not yet have.
     */
    @Override
    public void connect(org.eclipse.lsp4j.services.LanguageClient client) {
        connectedClient = client;
    }

    /** The connected client, or null before {@code connect} — progress must tolerate both. */
    public org.eclipse.lsp4j.services.LanguageClient connectedClient() {
        return connectedClient;
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // Process lifetime is owned by the launcher in vega.app.
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    /** Token legend shared with the client. Order defines the wire encoding, so it is fixed. */
    public static final class TokenLegend {
        public static final List<String> TYPES =
                List.of("keyword", "type", "function", "variable", "string", "number", "comment", "operator");
        public static final List<String> MODIFIERS = List.of("declaration", "static", "readonly");

        private TokenLegend() {}
    }
}
