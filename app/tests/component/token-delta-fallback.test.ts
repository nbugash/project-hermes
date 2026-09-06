import { describe, expect, it } from 'vitest';
import { resolveTokenResponse } from '../../src/renderer/editor/semantic-tokens';

/**
 * A server may answer a delta request with a full result — after a cache eviction, a restart, or
 * simply because a full result is smaller. The client must accept both shapes, and must never treat
 * a full result as a delta: doing so applies token data as if it were edit indices.
 */
describe('resolveTokenResponse', () => {
  const previous = new Uint32Array([1, 0, 4, 0, 0]);

  it('takes a full result as the new baseline', () => {
    const resolved = resolveTokenResponse(previous, {
      resultId: 'r2',
      data: [2, 0, 3, 1, 0],
    });

    expect(Array.from(resolved.data)).toEqual([2, 0, 3, 1, 0]);
    expect(resolved.resultId).toBe('r2');
  });

  it('applies a delta against the previous baseline', () => {
    const resolved = resolveTokenResponse(previous, {
      resultId: 'r2',
      edits: [{ start: 0, deleteCount: 5, data: [3, 3, 3, 3, 3] }],
    });

    expect(Array.from(resolved.data)).toEqual([3, 3, 3, 3, 3]);
  });

  it('requires a full refresh when a delta arrives with no baseline held', () => {
    const resolved = resolveTokenResponse(null, {
      resultId: 'r2',
      edits: [{ start: 0, deleteCount: 5, data: [3, 3, 3, 3, 3] }],
    });

    // Applying edits to nothing would silently produce a wrong array; asking for a full result is
    // the only way back to a correct highlight.
    expect(resolved.needsFullRefresh).toBe(true);
  });

  it('does not request a refresh when a full result arrives with no baseline', () => {
    const resolved = resolveTokenResponse(null, { resultId: 'r1', data: [1, 0, 4, 0, 0] });

    expect(resolved.needsFullRefresh).toBe(false);
    expect(Array.from(resolved.data)).toEqual([1, 0, 4, 0, 0]);
  });

  it('treats an empty delta as no change', () => {
    const resolved = resolveTokenResponse(previous, { resultId: 'r2', edits: [] });

    expect(Array.from(resolved.data)).toEqual(Array.from(previous));
  });
});
