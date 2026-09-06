package vega.lsp;

import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;
import vega.protocol.ReadDocumentParams;
import vega.protocol.ReadDocumentResult;
import vega.protocol.SaveDocumentParams;
import vega.protocol.SaveDocumentResult;

/**
 * The server surface: standard LSP plus Vega's two custom file-I/O requests.
 *
 * <p>Declared as one interface so the client proxy and the server implementation are typed against
 * the same contract. The {@code vega/*} methods exist because the editor process performs no file
 * I/O of its own (FR-002); they are the only operations standard LSP cannot express for this spike.
 */
public interface VegaServerApi extends LanguageServer {

    @JsonRequest("vega/readDocument")
    CompletableFuture<ReadDocumentResult> readDocument(ReadDocumentParams params);

    @JsonRequest("vega/saveDocument")
    CompletableFuture<SaveDocumentResult> saveDocument(SaveDocumentParams params);
}
