import { describe, expect, it, vi } from 'vitest';
import { VegaSemanticTokensProvider } from '../../src/renderer/editor/SemanticTokensProvider';

const LEGEND = { tokenTypes: ['keyword'], tokenModifiers: [] } as never;

function modelFor(uri: string) {
  return { uri: { toString: () => uri } } as never;
}

describe('VegaSemanticTokensProvider', () => {
  it('returns a full result and remembers it as the baseline', async () => {
    const requester = {
      requestTokens: vi.fn().mockResolvedValue({ resultId: 'r1', data: [1, 0, 4, 0, 0] }),
      documentVersion: () => 1,
    };
    const provider = new VegaSemanticTokensProvider(requester, LEGEND);

    const tokens = await provider.provideDocumentSemanticTokens(modelFor('file:///A.java'), null);

    expect(Array.from(tokens!.data)).toEqual([1, 0, 4, 0, 0]);
    expect(tokens!.resultId).toBe('r1');
  });

  it('applies a delta against the baseline held for the quoted result id', async () => {
    const requester = {
      requestTokens: vi
        .fn()
        .mockResolvedValueOnce({ resultId: 'r1', data: [1, 0, 4, 0, 0] })
        .mockResolvedValueOnce({
          resultId: 'r2',
          edits: [{ start: 0, deleteCount: 5, data: [2, 0, 3, 0, 0] }],
        }),
      documentVersion: () => 1,
    };
    const provider = new VegaSemanticTokensProvider(requester, LEGEND);

    await provider.provideDocumentSemanticTokens(modelFor('file:///A.java'), null);
    const updated = await provider.provideDocumentSemanticTokens(modelFor('file:///A.java'), 'r1');

    expect(Array.from(updated!.data)).toEqual([2, 0, 3, 0, 0]);
  });

  it('discards a result whose version was superseded while it was in flight', async () => {
    let version = 1;
    const requester = {
      requestTokens: vi.fn().mockImplementation(async () => {
        // The user types while the request is outstanding.
        version = 2;
        return { resultId: 'r1', data: [1, 0, 4, 0, 0] };
      }),
      documentVersion: () => version,
    };
    const provider = new VegaSemanticTokensProvider(requester, LEGEND);
    provider.observeVersion(1);

    const tokens = await provider.provideDocumentSemanticTokens(modelFor('file:///A.java'), null);

    expect(tokens).toBeNull();
  });

  it('asks for a full refresh when a delta arrives with no baseline held', async () => {
    const requester = {
      requestTokens: vi.fn().mockResolvedValue({
        resultId: 'r2',
        edits: [{ start: 0, deleteCount: 5, data: [2, 0, 3, 0, 0] }],
      }),
      documentVersion: () => 1,
    };
    const provider = new VegaSemanticTokensProvider(requester, LEGEND);

    const tokens = await provider.provideDocumentSemanticTokens(
      modelFor('file:///A.java'),
      'evicted-id',
    );

    expect(tokens).toBeNull();
  });

  it('forgets a baseline once the editor releases it', async () => {
    const requester = {
      requestTokens: vi
        .fn()
        .mockResolvedValueOnce({ resultId: 'r1', data: [1, 0, 4, 0, 0] })
        .mockResolvedValueOnce({ resultId: 'r2', edits: [{ start: 0, deleteCount: 5 }] }),
      documentVersion: () => 1,
    };
    const provider = new VegaSemanticTokensProvider(requester, LEGEND);

    await provider.provideDocumentSemanticTokens(modelFor('file:///A.java'), null);
    provider.releaseDocumentSemanticTokens('r1');

    // Without the baseline the delta cannot be applied, so the provider must ask for a full result
    // rather than apply edits to an array it no longer has.
    const tokens = await provider.provideDocumentSemanticTokens(modelFor('file:///A.java'), 'r1');
    expect(tokens).toBeNull();
  });
});
