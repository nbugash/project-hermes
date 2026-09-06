import type * as monaco from 'monaco-editor';
import {
  ResultGate,
  resolveTokenResponse,
  type TokenResponse,
} from './semantic-tokens';

/**
 * Monaco semantic-tokens provider backed by the LSP connection.
 *
 * Monaco asks for a delta by quoting the last result id it holds, and the server may answer with
 * either a delta or a full result. Both shapes are handled here; the version gate above them
 * discards anything computed against text the buffer has already moved past.
 */
export interface TokenRequester {
  /** Requests tokens, quoting a previous result id when one is held. */
  requestTokens(uri: string, previousResultId: string | undefined): Promise<TokenResponse>;
  /** Version of the document the last request was issued against. */
  documentVersion(uri: string): number;
}

export class VegaSemanticTokensProvider implements monaco.languages.DocumentSemanticTokensProvider {
  private readonly baselines = new Map<string, Uint32Array>();
  private readonly gate = new ResultGate();

  constructor(
    private readonly requester: TokenRequester,
    private readonly legend: monaco.languages.SemanticTokensLegend,
  ) {}

  getLegend(): monaco.languages.SemanticTokensLegend {
    return this.legend;
  }

  async provideDocumentSemanticTokens(
    model: monaco.editor.ITextModel,
    lastResultId: string | null,
  ): Promise<monaco.languages.SemanticTokens | null> {
    const uri = model.uri.toString();
    const requestedAtVersion = this.requester.documentVersion(uri);

    const response = await this.requester.requestTokens(uri, lastResultId ?? undefined);

    // Re-read the version only now. Checking it before the await compares a value against itself and
    // can never detect the case this exists for: the user typing while the request was outstanding.
    this.gate.observeVersion(this.requester.documentVersion(uri));
    if (!this.gate.accepts(requestedAtVersion)) {
      return null;
    }

    const previous = lastResultId === null ? null : (this.baselines.get(lastResultId) ?? null);
    const resolved = resolveTokenResponse(previous, response);

    if (resolved.needsFullRefresh) {
      // Returning null makes Monaco re-ask without a previous id, which is the full result we need.
      return null;
    }

    if (resolved.resultId === undefined) {
      return { data: resolved.data };
    }
    this.baselines.set(resolved.resultId, resolved.data);
    return { resultId: resolved.resultId, data: resolved.data };
  }

  /**
   * Monaco calls this when it no longer holds the baseline for a result id.
   *
   * Dropping it here is what keeps the map from growing for the lifetime of the session — one full
   * token array per edit on a 50,000-line file is a leak measured in megabytes per minute of typing.
   */
  releaseDocumentSemanticTokens(resultId: string | undefined): void {
    if (resultId !== undefined) {
      this.baselines.delete(resultId);
    }
  }

  /** Called by the editor host when the document version advances. */
  observeVersion(version: number): void {
    this.gate.observeVersion(version);
  }
}
