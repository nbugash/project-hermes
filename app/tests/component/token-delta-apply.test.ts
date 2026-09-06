import { describe, expect, it } from 'vitest';
import { applyTokenEdits } from '../../src/renderer/editor/semantic-tokens';

/**
 * Semantic-token edits carry indices into the array as it was *before* any of them were applied.
 *
 * Applying them front-to-back shifts every later index by the size of the earlier edits, so the
 * array is rebuilt from the wrong positions. The result is not stale highlighting — which self-
 * corrects on the next edit — but scrambled highlighting that persists until a full refresh.
 */
describe('applyTokenEdits', () => {
  const previous = new Uint32Array([1, 0, 4, 0, 0, 1, 0, 4, 1, 0, 1, 0, 4, 2, 0]);

  it('applies a single replacement', () => {
    const result = applyTokenEdits(previous, [{ start: 5, deleteCount: 5, data: [2, 1, 3, 4, 0] }]);

    expect(Array.from(result)).toEqual([1, 0, 4, 0, 0, 2, 1, 3, 4, 0, 1, 0, 4, 2, 0]);
  });

  it('applies multiple edits back-to-front regardless of the order received', () => {
    // Deliberately given out of order, as a server is entitled to send them.
    const edits = [
      { start: 10, deleteCount: 5, data: [9, 9, 9, 9, 9] },
      { start: 0, deleteCount: 5, data: [8, 8, 8, 8, 8] },
    ];

    const result = applyTokenEdits(previous, edits);

    expect(Array.from(result)).toEqual([8, 8, 8, 8, 8, 1, 0, 4, 1, 0, 9, 9, 9, 9, 9]);
  });

  it('treats a missing data field as a pure deletion', () => {
    const result = applyTokenEdits(previous, [{ start: 5, deleteCount: 5 }]);

    expect(Array.from(result)).toEqual([1, 0, 4, 0, 0, 1, 0, 4, 2, 0]);
  });

  it('applies an insertion with no deletion', () => {
    const result = applyTokenEdits(previous, [{ start: 5, deleteCount: 0, data: [7, 7, 7, 7, 7] }]);

    expect(result).toHaveLength(20);
    expect(Array.from(result.slice(5, 10))).toEqual([7, 7, 7, 7, 7]);
  });

  it('returns the previous array unchanged when there are no edits', () => {
    expect(Array.from(applyTokenEdits(previous, []))).toEqual(Array.from(previous));
  });

  it('handles an edit that clears the entire array', () => {
    expect(applyTokenEdits(previous, [{ start: 0, deleteCount: 15 }])).toHaveLength(0);
  });
});
