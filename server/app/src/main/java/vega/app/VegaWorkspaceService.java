package vega.app;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Workspace notifications the spike does not act on.
 *
 * <p>LSP requires the service to exist; this spike has no configuration to reload and does not watch
 * files. Accepting and ignoring is the honest implementation — the alternative, throwing, would
 * disconnect clients that legitimately send these.
 */
final class VegaWorkspaceService implements WorkspaceService {

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {}

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}
}
