import { describe, expect, it } from 'vitest';
import { TokenStore } from '../../src/renderer/editor/TokenStore';

/**
 * The store holds the full-document token array and the baselines deltas are expressed against.
 *
 * Its job is to make scrolling into never-visited regions show styled text immediately: the viewport
 * request covers what is on screen, and the background full request fills in everything else. If the
 * store loses a baseline the client cannot apply the next delta, and if it keeps every baseline it
 * leaks a full token array per keystroke.
 */
describe('TokenStore', () => {
  it('starts with nothing and reports no baseline', () => {
    const store = new TokenStore();

    expect(store.current()).toBeNull();
    expect(store.baselineFor('anything')).toBeNull();
  });

  it('retains a full result as the current tokens and as a baseline', () => {
    const store = new TokenStore();
    const data = Uint32Array.from([1, 0, 4, 0, 0]);

    store.accept('r1', data);

    expect(store.current()).toEqual(data);
    expect(store.baselineFor('r1')).toEqual(data);
  });

  it('replaces the current tokens when a newer result arrives', () => {
    const store = new TokenStore();
    store.accept('r1', Uint32Array.from([1, 0, 4, 0, 0]));
    store.accept('r2', Uint32Array.from([2, 0, 3, 1, 0]));

    expect(Array.from(store.current()!)).toEqual([2, 0, 3, 1, 0]);
  });

  it('bounds how many baselines it keeps', () => {
    const store = new TokenStore(2);

    store.accept('r1', Uint32Array.from([1, 0, 4, 0, 0]));
    store.accept('r2', Uint32Array.from([2, 0, 4, 0, 0]));
    store.accept('r3', Uint32Array.from([3, 0, 4, 0, 0]));

    // One full token array per keystroke on a 50,000-line file is megabytes a minute; the oldest
    // baseline is dropped rather than held for a delta no client will ask for.
    expect(store.baselineFor('r1')).toBeNull();
    expect(store.baselineFor('r3')).not.toBeNull();
  });

  it('forgets a baseline when the editor releases it', () => {
    const store = new TokenStore();
    store.accept('r1', Uint32Array.from([1, 0, 4, 0, 0]));

    store.release('r1');

    expect(store.baselineFor('r1')).toBeNull();
  });

  it('keeps the current tokens after a released baseline, so painting still works', () => {
    const store = new TokenStore();
    store.accept('r1', Uint32Array.from([1, 0, 4, 0, 0]));

    store.release('r1');

    // Releasing a baseline means "I cannot ask for a delta against this", not "forget the colours
    // already on screen".
    expect(store.current()).not.toBeNull();
  });

  it('reports whether the full document has been received', () => {
    const store = new TokenStore();
    expect(store.hasFullDocument()).toBe(false);

    store.acceptFullDocument('r1', Uint32Array.from([1, 0, 4, 0, 0]));

    // Until this is true, scrolling into an unvisited region can reach text no request has covered.
    expect(store.hasFullDocument()).toBe(true);
  });
});
