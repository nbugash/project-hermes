import { describe, expect, it, vi } from 'vitest';
import { loadDocument } from '../../src/renderer/editor/load-document';

/**
 * FR-002 and FR-021: the editor process performs no filesystem read of the document. The renderer
 * has no filesystem access to misuse, so what these tests actually pin down is that loading goes
 * through the bridge and that its metadata survives — the metadata is what makes a later save
 * byte-identical.
 */
describe('loadDocument', () => {
  const content = {
    text: 'class A {}\n',
    encoding: 'UTF-8',
    lineEnding: 'LF' as const,
    hasTrailingNewline: true,
    contentHash: 'abc123',
  };

  it('reads through the bridge rather than the filesystem', async () => {
    const readDocument = vi.fn().mockResolvedValue(content);

    const loaded = await loadDocument({ readDocument } as never, 'file:///A.java');

    expect(readDocument).toHaveBeenCalledWith('file:///A.java');
    expect(loaded.text).toBe('class A {}\n');
  });

  it('preserves the metadata needed to save the file back unchanged', async () => {
    const readDocument = vi.fn().mockResolvedValue(content);

    const loaded = await loadDocument({ readDocument } as never, 'file:///A.java');

    expect(loaded.metadata).toEqual({
      encoding: 'UTF-8',
      lineEnding: 'LF',
      hasTrailingNewline: true,
      contentHash: 'abc123',
    });
  });

  it('reports a failed read with the uri that failed', async () => {
    const readDocument = vi.fn().mockRejectedValue(new Error('Backend is not connected'));

    await expect(loadDocument({ readDocument } as never, 'file:///Missing.java')).rejects.toThrow(
      /file:\/\/\/Missing\.java/,
    );
  });
});
