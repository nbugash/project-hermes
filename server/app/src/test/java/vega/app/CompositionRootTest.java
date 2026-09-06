package vega.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import vega.core.document.DocumentService;
import vega.lsp.VegaLanguageServer;

/**
 * The composition root is the one place adapters attach to core ports, so it is also the one place
 * a missing binding can leave the backend compiling perfectly and serving nothing. These tests boot
 * the real graph rather than asserting on the module in isolation.
 */
class CompositionRootTest {

    @Test
    void buildsALanguageServerWithEveryPortBound() {
        VegaComponent component = VegaComponent.create();

        // Resolving the server forces Dagger to satisfy the whole chain: file gateway, syntax
        // parser, document service and handlers. An unbound port fails here at construction.
        VegaLanguageServer server = component.languageServer();

        assertNotNull(server);
        assertNotNull(server.getTextDocumentService());
        assertNotNull(server.getWorkspaceService());

        component.cpuWorkerPool().close();
    }

    @Test
    void sharesOneDocumentServiceAcrossHandlers() {
        VegaComponent component = VegaComponent.create();

        // Two mirrors of the same buffer would desynchronise silently: reads would answer from one
        // and edits apply to the other, and the divergence would only surface as a corrupted save.
        DocumentService first = component.documentService();
        DocumentService second = component.documentService();

        assertSame(first, second, "the document mirror must be a singleton");

        component.cpuWorkerPool().close();
    }

    @Test
    void advertisesTheNegotiatedPositionEncoding() throws Exception {
        VegaComponent component = VegaComponent.create();

        var result =
                component
                        .languageServer()
                        .initialize(new org.eclipse.lsp4j.InitializeParams())
                        .get();

        assertEquals(
                org.eclipse.lsp4j.PositionEncodingKind.UTF16,
                result.getCapabilities().getPositionEncoding());
        assertNotNull(
                result.getCapabilities().getSemanticTokensProvider(),
                "semantic tokens must be advertised or the editor never requests them");

        component.cpuWorkerPool().close();
    }
}
