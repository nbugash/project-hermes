import type { TokenStore } from './TokenStore';

export interface FullTokenRequest {
  (uri: string): Promise<{ resultId?: string; data: readonly number[] }>;
}

/**
 * Fills in tokens for the parts of the document the viewport request did not cover.
 *
 * Issued immediately after the first paint rather than when the user reaches an unstyled region: on
 * a 50,000-line file a scroll gesture outruns a request round-trip, so waiting for the need to
 * appear guarantees the user sees unstyled text at least once.
 *
 * Never rejects. This is a background fill whose failure costs colour in regions the user may never
 * visit; surfacing it as an unhandled rejection would be a worse outcome than the missing colour.
 */
export async function requestFullTokensInBackground(
  request: FullTokenRequest,
  uri: string,
  store: TokenStore,
): Promise<void> {
  if (store.hasFullDocument()) {
    return;
  }

  try {
    const response = await request(uri);
    if (response.resultId !== undefined) {
      store.acceptFullDocument(response.resultId, Uint32Array.from(response.data));
    }
  } catch {
    // Deliberately swallowed: the viewport tokens already on screen remain correct, and the fill
    // is retried the next time the document is re-analysed.
  }
}
