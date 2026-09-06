import { describe, expect, it } from 'vitest';
import { describeBackendStatus } from '../../src/renderer/shell/backend-status';

/**
 * The window is interactive before the backend is ready (FR-004), so there is a real window in which
 * the editor works and highlighting does not. Saying so is the difference between a product that
 * looks like it is starting and one that looks broken.
 */
describe('describeBackendStatus', () => {
  it('reports a starting backend as degraded but usable', () => {
    const state = describeBackendStatus('starting');

    expect(state.degraded).toBe(true);
    expect(state.editable).toBe(true);
    expect(state.label).toMatch(/start/i);
  });

  it('reports indexing as degraded, since highlighting is still incomplete', () => {
    expect(describeBackendStatus('indexing').degraded).toBe(true);
  });

  it('reports ready as neither degraded nor blocked', () => {
    const state = describeBackendStatus('ready');

    expect(state.degraded).toBe(false);
    expect(state.editable).toBe(true);
  });

  it('keeps the document editable when the backend has failed', () => {
    // Losing highlighting must not cost the user their ability to type or their unsaved work; a
    // dead backend that also freezes the editor turns a degraded session into data loss.
    const state = describeBackendStatus('failed', 'Backend exited with code 1');

    expect(state.editable).toBe(true);
    expect(state.degraded).toBe(true);
    expect(state.label).toMatch(/exited with code 1/);
  });

  it('surfaces failure detail rather than a generic message', () => {
    expect(describeBackendStatus('failed', 'spawn java ENOENT').label).toContain('spawn java ENOENT');
  });
})
