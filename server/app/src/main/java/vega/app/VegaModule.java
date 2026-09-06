package vega.app;

import dagger.Module;
import dagger.Provides;
import io.vertx.core.Vertx;
import javax.inject.Singleton;
import org.eclipse.lsp4j.services.WorkspaceService;
import vega.core.document.DocumentService;
import vega.core.highlight.HighlightService;
import vega.core.port.FileGatewayPort;
import vega.core.port.SyntaxParserPort;
import vega.fs.FileGatewayAdapter;
import vega.lsp.ProgressReporter;
import vega.lsp.SemanticTokensHandler;
import vega.lsp.VegaDocumentHandlers;
import vega.lsp.VegaLanguageServer;
import vega.lsp.VegaTextDocumentService;
import vega.syntax.TreeSitterSyntaxAdapter;

/**
 * Composition root bindings.
 *
 * <p>This is the only place adapters are attached to core ports. Dagger must not be used to reach
 * across the hexagonal boundary anywhere else: injection that resolves an adapter inside the core
 * would satisfy the compiler while defeating Principle III, and ArchUnit is what catches it.
 */
@Module
public final class VegaModule {

    @Provides
    @Singleton
    static Vertx provideVertx() {
        return Vertx.vertx();
    }

    @Provides
    @Singleton
    static CpuWorkerPool provideCpuWorkerPool(Vertx vertx) {
        return new CpuWorkerPool(vertx);
    }

    @Provides
    @Singleton
    static FileGatewayPort provideFileGateway() {
        return new FileGatewayAdapter();
    }

    @Provides
    @Singleton
    static SyntaxParserPort provideSyntaxParser() {
        return new TreeSitterSyntaxAdapter();
    }

    /**
     * One mirror of the editor's buffer, shared by every handler. Two instances would drift apart
     * silently — reads answered from one, edits applied to the other — and only surface as a
     * corrupted save.
     */
    @Provides
    @Singleton
    static DocumentService provideDocumentService(FileGatewayPort fileGateway) {
        return new DocumentService(fileGateway);
    }

    @Provides
    @Singleton
    static HighlightService provideHighlightService(SyntaxParserPort parser) {
        return new HighlightService(parser);
    }

    @Provides
    @Singleton
    static VegaDocumentHandlers provideDocumentHandlers(
            DocumentService documents, FileGatewayPort files) {
        return new VegaDocumentHandlers(documents, files);
    }

    @Provides
    @Singleton
    static SemanticTokensHandler provideSemanticTokensHandler() {
        return new SemanticTokensHandler(VegaLanguageServer.TokenLegend.TYPES);
    }

    @Provides
    @Singleton
    static WorkspaceService provideWorkspaceService() {
        return new VegaWorkspaceService();
    }

    /**
     * The server and its progress reporter are mutually dependent: the reporter needs the client,
     * which lsp4j only supplies to the server after construction. Resolving that with a holder here
     * keeps the cycle in the composition root, where wiring problems belong, rather than pushing a
     * setter onto the adapter.
     */
    @Provides
    @Singleton
    static VegaLanguageServer provideLanguageServer(
            DocumentService documents,
            SyntaxParserPort parser,
            SemanticTokensHandler semanticTokens,
            VegaDocumentHandlers handlers,
            WorkspaceService workspace,
            HighlightService highlights) {
        VegaLanguageServer[] holder = new VegaLanguageServer[1];
        ProgressReporter progress =
                new ProgressReporter(() -> holder[0] == null ? null : holder[0].connectedClient());
        VegaTextDocumentService textDocuments =
                new VegaTextDocumentService(documents, parser, semanticTokens, progress, highlights);
        holder[0] = new VegaLanguageServer(textDocuments, workspace, handlers);
        return holder[0];
    }
}
