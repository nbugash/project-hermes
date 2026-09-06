import { describe, expect, it, vi } from 'vitest';
import { TokenStore } from '../../src/renderer/editor/TokenStore';
import { requestFullTokensInBackground } from '../../src/renderer/editor/backgroundFullTokens';

/**
 * The viewport request paints what is on screen; the full request fills in everything else.
 *
 * Without the second one, scrolling into a region no request has covered shows unstyled text. It is
 * issued immediately after the first paint rather than on demand, because on a 50,000-line file the
 * user can scroll faster than a request round-trip.
 */
describe('requestFullTokensInBackground', () => {
  it('stores the full document tokens when the request succeeds', async () => {
    const store = new TokenStore();
    const request = vi.fn().mockResolvedValue({ resultId: 'full-1', data: [1, 0, 4, 0, 0] });

    await requestFullTokensInBackground(request, 'file:///A.java', store);

    expect(store.hasFullDocument()).toBe(true);
    expect(Array.from(store.current()!)).toEqual([1, 0, 4, 0, 0]);
  });

  it('does not re-request when the full document is already held', async () => {
    const store = new TokenStore();
    store.acceptFullDocument('full-1', Uint32Array.from([1, 0, 4, 0, 0]));
    const request = vi.fn();

    await requestFullTokensInBackground(request, 'file:///A.java', store);

    expect(request).not.toHaveBeenCalled();
  });

  it('leaves the store untouched when the request fails', async () => {
    const store = new TokenStore();
    const request = vi.fn().mockRejectedValue(new Error('backend went away'));

    await requestFullTokensInBackground(request, 'file:///A.java', store);

    // A failed background fill must not clear what is already painted; the viewport tokens are
    // still correct for what the user can see.
    expect(store.hasFullDocument()).toBe(false);
    expect(store.current()).toBeNull();
  });

  it('never rejects, so a background failure cannot become an unhandled rejection', async () => {
    const request = vi.fn().mockRejectedValue(new Error('backend went away'));

    await expect(
      requestFullTokensInBackground(request, 'file:///A.java', new TokenStore()),
    ).resolves.toBeUndefined();
  });
});
