import { app, BrowserWindow, dialog, ipcMain } from 'electron';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { initializeBackend, spawnBackend, type BackendHandle } from '../protocol-client/connection.js';
import { resolveInitialFile } from './cli.js';

/**
 * Electron main process: owns the window, the backend process, and the only stdio channel to it.
 *
 * The ordering here is the cold-start budget made concrete. The window is created and shown before
 * the backend has finished starting, because FR-004 requires an interactive window within 2 s and a
 * JVM plus a 50,000-line parse does not fit in that. The renderer therefore has to tolerate a
 * backend that is not ready yet, which is why status is pushed to it rather than awaited here.
 */

const isDevelopment = !app.isPackaged;
let backend: BackendHandle | null = null;
let mainWindow: BrowserWindow | null = null;

/**
 * File named on the command line, held until the renderer asks for it.
 *
 * Pushed at `did-finish-load` this was lost outright: the renderer registers its listener from a
 * React effect, which can run after that event has already fired, and a notification with no
 * listener simply vanishes. Holding it and letting the renderer pull removes the ordering question
 * entirely.
 */
let initialFileUri: string | null = null;

type BackendStatus = 'starting' | 'indexing' | 'ready' | 'failed';

/**
 * Latest status, held so the renderer can ask for it.
 *
 * Pushing alone loses any status that changes before the renderer's listener is registered — and the
 * fastest failures are exactly the ones that arrive first. A backend that cannot be spawned at all
 * reports its failure within milliseconds, and the window would sit on "starting" forever.
 */
let currentStatus: { status: BackendStatus; detail?: string } = { status: 'starting' };

function publishStatus(status: BackendStatus, detail?: string): void {
  currentStatus = detail === undefined ? { status } : { status, detail };
  mainWindow?.webContents.send('vega:backendStatus', status, detail);
}

function createWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: 1280,
    height: 800,
    // Shown immediately rather than on ready-to-show: a hidden window waiting on the backend is
    // exactly the blank-screen start the cold-start budget forbids.
    show: true,
    webPreferences: {
      preload: path.join(path.dirname(fileURLToPath(import.meta.url)), '../preload/bridge.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  if (isDevelopment && process.env.VITE_DEV_SERVER_URL) {
    void window.loadURL(process.env.VITE_DEV_SERVER_URL);
  } else {
    void window.loadFile(path.join(path.dirname(fileURLToPath(import.meta.url)), '../renderer/index.html'));
  }
  return window;
}

async function startBackend(rootUri: string): Promise<void> {
  publishStatus('starting');
  try {
    backend = spawnBackend({
      command: process.env.VEGA_BACKEND_COMMAND ?? 'java',
      args: (process.env.VEGA_BACKEND_ARGS ?? '-jar,server/app/build/libs/app.jar').split(','),
    });

    // The backend is spawned with piped stdio, so anything it writes to stderr goes into a pipe
    // nobody reads: its logs, its stack traces and its startup failures all vanish. Forwarding them
    // is the difference between diagnosing a backend problem and guessing at one — and an unread
    // pipe eventually fills and blocks the process that is writing to it.
    backend.process.stderr.on('data', (chunk: Buffer) => {
      process.stderr.write(`[backend] ${chunk.toString()}`);
    });

    backend.process.on('exit', (code) => {
      // A dead backend must be visible. Left unreported it presents as highlighting that never
      // arrives, which reads as a hang rather than a failure.
      publishStatus('failed', `Backend exited with code ${code ?? 'unknown'}`);
    });

    await initializeBackend(backend, rootUri);
    publishStatus('ready');
  } catch (error) {
    publishStatus('failed', error instanceof Error ? error.message : String(error));
  }
}

/**
 * Sends a request, failing fast if the backend dies while it is in flight.
 *
 * Without the race a request to a backend that never answers waits forever, and the renderer shows a
 * document that is permanently "loading" — the silent-hang failure mode that is strictly worse than
 * an error, because nothing in the UI ever changes to say something went wrong.
 */
async function request<T>(method: string, params: unknown): Promise<T> {
  if (!backend) {
    throw new Error('Backend is not connected');
  }
  const handle = backend;

  try {
    // Inside the try deliberately: on a closed connection this throws synchronously rather than
    // returning a rejected promise, and a call sited above the try would escape the wrapping below
    // with the transport's own unhelpful message.
    const inFlight = handle.connection.sendRequest(method, params) as Promise<T>;
    inFlight.catch(() => undefined);

    return await Promise.race([
      inFlight,
      handle.failed.then((reason) => {
        throw reason;
      }),
    ]);
  } catch (error) {
    // The transport's own message for a dead backend is "Connection is closed", which is true and
    // useless: it names neither the operation nor the cause. Whichever of the two failures arrives
    // first, the message the user sees says what was being done and, where known, why it stopped.
    const cause = handle.failureReason() ?? error;
    const detail = cause instanceof Error ? cause.message : String(cause);
    throw new Error(`Backend request ${method} failed: ${detail}`, { cause });
  }
}

function registerBridgeHandlers(): void {
  ipcMain.handle('vega:readDocument', (_event, uri: string) =>
    request('vega/readDocument', { uri }),
  );

  ipcMain.handle('vega:saveDocument', (_event, payload: unknown) =>
    request('vega/saveDocument', payload),
  );

  ipcMain.handle('vega:initialFile', () => initialFileUri);

  ipcMain.handle('vega:backendStatus', () => currentStatus);

  ipcMain.handle('vega:didOpen', (_event, uri: string, text: string, version: number) => {
    backend?.connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri, languageId: 'java', version, text },
    });
  });

  ipcMain.handle(
    'vega:didChange',
    (_event, uri: string, version: number, changes: unknown[]) => {
      backend?.connection.sendNotification('textDocument/didChange', {
        textDocument: { uri, version },
        contentChanges: changes,
      });
    },
  );

  ipcMain.handle('vega:semanticTokens', (_event, uri: string, previousResultId?: string) =>
    previousResultId === undefined
      ? request('textDocument/semanticTokens/full', { textDocument: { uri } })
      : request('textDocument/semanticTokens/full/delta', {
          textDocument: { uri },
          previousResultId,
        }),
  );

  ipcMain.handle('vega:openFileDialog', async () => {
    const result = await dialog.showOpenDialog({
      properties: ['openFile'],
      filters: [{ name: 'Java', extensions: ['java'] }],
    });
    const [chosen] = result.filePaths;
    return result.canceled || chosen === undefined ? null : pathToFileURL(chosen).toString();
  });
}

void app.whenReady().then(async () => {
  registerBridgeHandlers();
  mainWindow = createWindow();

  const initialFile = resolveInitialFile(process.argv, isDevelopment);
  const rootUri = pathToFileURL(initialFile ? path.dirname(initialFile) : process.cwd()).toString();

  initialFileUri = initialFile === null ? null : pathToFileURL(initialFile).toString();

  // Deliberately not awaited before the window exists: startup must not block on the backend.
  void startBackend(rootUri);
});

app.on('window-all-closed', () => {
  backend?.dispose();
  app.quit();
});
