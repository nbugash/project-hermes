import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { Writable } from 'node:stream';
import {
  createMessageConnection,
  StreamMessageReader,
  StreamMessageWriter,
  type MessageConnection,
} from 'vscode-jsonrpc/node';
import { CLIENT_PROTOCOL_VERSION, assertProtocolCompatible } from './version.js';

/**
 * The ONLY module permitted to spawn the backend or open the stdio channel to it.
 *
 * Constitution Principle I forbids ad-hoc side channels; concentrating the transport in one module
 * is the mechanical form of that rule. This runs in the Electron main/utility process, never the
 * renderer: a sandboxed renderer has no Node, and keeping it here makes the channel structurally
 * unreachable from UI code.
 */
export interface BackendHandle {
  connection: MessageConnection;
  process: ChildProcessWithoutNullStreams;
  /**
   * Resolves with the reason the backend died, and never rejects.
   *
   * Deliberately a resolving promise: a rejecting one that nothing happens to be awaiting becomes an
   * unhandled rejection, which bypasses the shell's status reporting and leaves the user with a
   * window that looks healthy and never highlights anything.
   */
  failed: Promise<Error>;
  /** The reason the backend died, if it already has. Synchronous, for error reporting. */
  failureReason(): Error | null;
  dispose(): void;
}

export interface SpawnOptions {
  command: string;
  args: readonly string[];
}

export function spawnBackend(options: SpawnOptions): BackendHandle {
  const child = spawn(options.command, [...options.args], {
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  let reason: Error | null = null;
  let settle: (reason: Error) => void = () => undefined;
  const failed = new Promise<Error>((resolve) => {
    settle = resolve;
  });
  const fail = (error: Error) => {
    reason ??= error;
    settle(error);
  };

  child.on('error', (cause) => {
    fail(new Error(`Backend process failed to start: ${cause.message}`, { cause }));
  });
  child.on('exit', (code, signal) => {
    fail(new Error(`Backend process exited (code ${code ?? 'none'}, signal ${signal ?? 'none'})`));
  });
  // A dead backend turns every subsequent write into an EPIPE on stdin. That is the same failure as
  // the exit above, already reported; letting it through as well would crash the main process.
  child.stdin.on('error', () => undefined);

  const connection = createMessageConnection(
    new StreamMessageReader(child.stdout),
    new StreamMessageWriter(writesThatSurviveBackendDeath(child)),
  );
  connection.listen();

  return {
    connection,
    process: child,
    failed,
    failureReason: () => reason,
    dispose() {
      connection.dispose();
      child.kill();
    },
  };
}

/**
 * Wraps the backend's stdin so writes after its death are dropped instead of throwing.
 *
 * vscode-jsonrpc queues writes internally and does not hand that promise to callers, so a write to a
 * dead process becomes an unhandled rejection no amount of `.catch()` at the call site can claim —
 * and in the Electron main process an unhandled rejection is fatal. Dropping the write is safe
 * precisely because the death is already reported through {@link BackendHandle.failed}; the message
 * had nowhere to go regardless.
 */
function writesThatSurviveBackendDeath(child: ChildProcessWithoutNullStreams): NodeJS.WritableStream {
  return new Writable({
    write(chunk, encoding, callback) {
      if (child.stdin.destroyed || child.exitCode !== null || child.signalCode !== null) {
        callback();
        return;
      }
      child.stdin.write(chunk, encoding, () => callback());
    },
  });
}

/**
 * Performs the LSP initialize handshake and enforces protocol compatibility.
 *
 * Deliberately separate from spawning: the window must become visible and interactive before this
 * completes (FR-004, Budget B3), so the shell awaits it out of band rather than blocking on it.
 */
export async function initializeBackend(
  handle: BackendHandle,
  rootUri: string,
): Promise<{ serverProtocolVersion: string }> {
  // Racing the handshake against process death is what turns "the backend never answered" into a
  // reported failure instead of a request that hangs until the user gives up.
  const request = handle.connection.sendRequest('initialize', {
    processId: process.pid,
    rootUri,
    capabilities: {},
    initializationOptions: { vegaProtocolVersion: CLIENT_PROTOCOL_VERSION },
  });

  // The loser of a race still settles. When the process dies, this request rejects too — with a
  // stream error that says nothing useful — and an unobserved rejection would crash the main
  // process. Claiming it here keeps the reported reason the process-level one.
  request.catch(() => undefined);

  const result = (await Promise.race([
    request,
    handle.failed.then((reason) => {
      throw reason;
    }),
  ])) as { capabilities?: { experimental?: { vegaProtocolVersion?: string } } };

  // LSP puts server-defined data under capabilities.experimental, which is where the server writes
  // this and therefore where it must be read from. Reading it from the top level yields undefined
  // and reports a version mismatch that blames the backend for a bug in this client.
  const serverProtocolVersion = result.capabilities?.experimental?.vegaProtocolVersion;
  if (serverProtocolVersion === undefined) {
    throw new Error(
      'Backend did not advertise a vega protocol version; refusing to start against an unknown server.',
    );
  }
  assertProtocolCompatible(serverProtocolVersion);

  // Fire-and-forget by protocol design, so its failure has to be claimed rather than awaited; the
  // backend's death is already reported through `failed`.
  void Promise.resolve(handle.connection.sendNotification('initialized', {})).catch(
    () => undefined,
  );
  return { serverProtocolVersion };
}
