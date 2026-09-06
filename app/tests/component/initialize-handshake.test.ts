import { describe, expect, it, vi } from 'vitest';
import { initializeBackend, type BackendHandle } from '../../src/protocol-client/connection';

/**
 * The initialize response nests the protocol version inside the server's capabilities, where LSP
 * puts server-defined data. Reading it from the wrong level does not fail loudly — it yields
 * undefined, falls back to a default, and surfaces as a version mismatch that blames the backend for
 * a bug in the client.
 */
function handleReturning(result: unknown): BackendHandle {
  const sendRequest = vi.fn().mockResolvedValue(result);
  const sendNotification = vi.fn();
  return {
    connection: { sendRequest, sendNotification } as unknown as BackendHandle['connection'],
    process: {} as BackendHandle['process'],
    // Never settles: this handle's backend is alive for the duration of the test.
    failed: new Promise<Error>(() => undefined),
    failureReason: () => null,
    dispose: vi.fn(),
  };
}

/** The exact shape the Java server returns, as asserted by BackendLaunchTest. */
const REAL_SERVER_RESULT = {
  capabilities: {
    positionEncoding: 'utf-16',
    experimental: { vegaProtocolVersion: '1.0.0' },
  },
};

describe('initializeBackend', () => {
  it('reads the protocol version from the server capabilities', async () => {
    const handle = handleReturning(REAL_SERVER_RESULT);

    const { serverProtocolVersion } = await initializeBackend(handle, 'file:///workspace');

    expect(serverProtocolVersion).toBe('1.0.0');
  });

  it('sends the initialized notification once the handshake succeeds', async () => {
    const handle = handleReturning(REAL_SERVER_RESULT);

    await initializeBackend(handle, 'file:///workspace');

    expect(handle.connection.sendNotification).toHaveBeenCalledWith('initialized', {});
  });

  it('rejects a backend that advertises no protocol version at all', async () => {
    const handle = handleReturning({ capabilities: {} });

    // Silently treating a missing version as compatible would let an unknown backend through, which
    // is the failure the handshake exists to prevent.
    await expect(initializeBackend(handle, 'file:///workspace')).rejects.toThrow(/protocol/i);
  });

  it('rejects a backend speaking a different major version', async () => {
    const handle = handleReturning({
      capabilities: { experimental: { vegaProtocolVersion: '2.0.0' } },
    });

    await expect(initializeBackend(handle, 'file:///workspace')).rejects.toThrow(/Major versions/);
  });
});
