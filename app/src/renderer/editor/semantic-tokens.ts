/**
 * Client-side handling of LSP semantic tokens and their deltas.
 *
 * Kept free of Monaco so the rules can be tested directly. The three rules here are the ones whose
 * violation produces silently wrong colour rather than an error: edit ordering, response-shape
 * handling, and version gating.
 */

export interface TokenEdit {
  start: number;
  deleteCount: number;
  data?: readonly number[];
}

export interface FullTokenResponse {
  resultId?: string;
  data: readonly number[];
}

export interface DeltaTokenResponse {
  resultId?: string;
  edits: readonly TokenEdit[];
}

export type TokenResponse = FullTokenResponse | DeltaTokenResponse;

export interface ResolvedTokens {
  data: Uint32Array;
  resultId: string | undefined;
  /** True when the response could not be applied and a full result must be requested. */
  needsFullRefresh: boolean;
}

/**
 * Applies token edits to a previous token array.
 *
 * Edits index the array as it was before *any* of them were applied, so they must be applied from
 * the highest start offset down. Applying them in received order shifts every later index by the
 * length change of the earlier ones, and the array is rebuilt from the wrong positions — scrambled
 * highlighting that persists rather than stale highlighting that self-corrects.
 */
export function applyTokenEdits(previous: Uint32Array, edits: readonly TokenEdit[]): Uint32Array {
  if (edits.length === 0) {
    return previous;
  }

  const ordered = [...edits].sort((a, b) => b.start - a.start);
  let result = Array.from(previous);
  for (const edit of ordered) {
    result.splice(edit.start, edit.deleteCount, ...(edit.data ?? []));
  }
  return Uint32Array.from(result);
}

function isFull(response: TokenResponse): response is FullTokenResponse {
  return 'data' in response && Array.isArray((response as FullTokenResponse).data);
}

/**
 * Normalises either response shape into a new token array.
 *
 * A server may answer a delta request with a full result — after a cache eviction, a restart, or
 * because a full result is simply smaller. Treating that full result as a delta would apply token
 * data as though it were edit indices.
 */
export function resolveTokenResponse(
  previous: Uint32Array | null,
  response: TokenResponse,
): ResolvedTokens {
  if (isFull(response)) {
    return {
      data: Uint32Array.from(response.data),
      resultId: response.resultId,
      needsFullRefresh: false,
    };
  }

  if (previous === null) {
    // Applying edits to nothing yields a plausible-looking but wrong array. Asking for a full
    // result is the only route back to a correct highlight.
    return { data: new Uint32Array(0), resultId: response.resultId, needsFullRefresh: true };
  }

  return {
    data: applyTokenEdits(previous, response.edits),
    resultId: response.resultId,
    needsFullRefresh: false,
  };
}

/**
 * Gates results on the document version they were computed from.
 *
 * A result that arrives after the user has typed again describes text that no longer exists, so its
 * offsets point at moved code. Future versions are rejected too: the client has not applied that
 * edit yet, so those offsets describe a buffer it does not have.
 */
export class ResultGate {
  private currentVersion: number | null = null;

  observeVersion(version: number): void {
    this.currentVersion = version;
  }

  accepts(resultVersion: number): boolean {
    return this.currentVersion !== null && resultVersion === this.currentVersion;
  }
}
