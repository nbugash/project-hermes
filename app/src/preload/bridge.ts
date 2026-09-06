import { contextBridge, ipcRenderer } from 'electron';

/**
 * The renderer's only route to the backend.
 *
 * The stdio channel lives in the main process (a sandboxed renderer has no Node), so the renderer
 * talks to it exclusively through this narrow, explicitly enumerated surface. Exposing the raw
 * connection would hand UI code a side channel, which Constitution Principle I forbids.
 */
export interface VegaBridge {
  readDocument(uri: string): Promise<{
    text: string;
    encoding: string;
    lineEnding: 'LF' | 'CRLF' | 'MIXED';
    hasTrailingNewline: boolean;
    contentHash: string;
  }>;

  saveDocument(request: {
    uri: string;
    version: number;
    expectedContentHash: string;
    baseContentHash: string;
  }): Promise<{ status: string; version?: number; contentHash?: string; reason?: string }>;

  onBackendStatus(
    listener: (status: 'starting' | 'indexing' | 'ready' | 'failed', detail?: string) => void,
  ): void;

  /** Resolves to a file URI, or null when the user cancels. */
  openFileDialog(): Promise<string | null>;

  /** The file named on the command line, or null. Pulled, so no notification can be missed. */
  initialFile(): Promise<string | null>;

  /** The current backend status, for statuses that changed before this listener existed. */
  backendStatus(): Promise<{ status: 'starting' | 'indexing' | 'ready' | 'failed'; detail?: string }>;

  /** Tells the backend a document is open, with the text the editor holds. */
  openDocument(uri: string, text: string, version: number): Promise<void>;

  /**
   * Sends an incremental change.
   *
   * Ranges, not whole text: sending 50,000 lines per keystroke is the whole-file work the design
   * exists to eliminate, and it would also make the mirror's version discipline meaningless.
   */
  changeDocument(
    uri: string,
    version: number,
    changes: readonly {
      range: {
        start: { line: number; character: number };
        end: { line: number; character: number };
      };
      text: string;
    }[],
  ): Promise<void>;

  /** Requests semantic tokens, quoting a previous result id when one is held. */
  semanticTokens(uri: string, previousResultId?: string): Promise<unknown>;
}

const bridge: VegaBridge = {
  readDocument: (uri) => ipcRenderer.invoke('vega:readDocument', uri),
  saveDocument: (request) => ipcRenderer.invoke('vega:saveDocument', request),
  onBackendStatus: (listener) => {
    ipcRenderer.on('vega:backendStatus', (_event, status, detail) => listener(status, detail));
  },
  openFileDialog: () => ipcRenderer.invoke('vega:openFileDialog'),
  initialFile: () => ipcRenderer.invoke('vega:initialFile'),
  backendStatus: () => ipcRenderer.invoke('vega:backendStatus'),
  openDocument: (uri, text, version) => ipcRenderer.invoke('vega:didOpen', uri, text, version),
  changeDocument: (uri, version, changes) =>
    ipcRenderer.invoke('vega:didChange', uri, version, changes),
  semanticTokens: (uri, previousResultId) =>
    ipcRenderer.invoke('vega:semanticTokens', uri, previousResultId),
};

contextBridge.exposeInMainWorld('vega', bridge);
