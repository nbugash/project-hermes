import { describe, expect, it } from 'vitest';
import { initializeBackend, spawnBackend } from '../../src/protocol-client/connection';

/**
 * A backend that fails to start is an expected condition, not an exceptional one: a missing JVM, a
 * jar that was never built, a wrong path. What must never happen is an unhandled rejection, because
 * that bypasses the shell's status reporting and leaves the user with a window that looks fine and
 * never highlights anything.
 */
describe('backend spawn failures', () => {
  it('rejects with a usable message when the command does not exist', async () => {
    const handle = spawnBackend({ command: 'definitely-not-a-real-command', args: [] });

    await expect(initializeBackend(handle, 'file:///workspace')).rejects.toThrow(/backend/i);

    handle.dispose();
  });

  it('does not leave an unhandled rejection behind when the process dies', async () => {
    const rejections: unknown[] = [];
    const capture = (reason: unknown) => rejections.push(reason);
    process.on('unhandledRejection', capture);

    const handle = spawnBackend({ command: 'definitely-not-a-real-command', args: [] });
    await initializeBackend(handle, 'file:///workspace').catch(() => undefined);
    handle.dispose();

    // Give the event loop a turn so any stray rejection would have surfaced by now.
    await new Promise((resolve) => setTimeout(resolve, 50));
    process.off('unhandledRejection', capture);

    expect(rejections).toEqual([]);
  });
});
