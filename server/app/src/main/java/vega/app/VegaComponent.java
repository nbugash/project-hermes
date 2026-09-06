package vega.app;

import dagger.Component;
import io.vertx.core.Vertx;
import javax.inject.Singleton;

/**
 * Application graph.
 *
 * <p>Adapter bindings are added here as the user stories that need them land. Keeping the graph
 * honest about what actually exists is deliberate: a binding for a port with no implementation would
 * be the speculative abstraction Principle VIII rejects. User Story 1 brought the filesystem
 * gateway, the tree-sitter syntax adapter and the LSP inbound adapter.
 */
@Singleton
@Component(modules = VegaModule.class)
public interface VegaComponent {

    Vertx vertx();

    CpuWorkerPool cpuWorkerPool();

    vega.core.document.DocumentService documentService();

    vega.lsp.VegaLanguageServer languageServer();

    static VegaComponent create() {
        return DaggerVegaComponent.create();
    }
}
