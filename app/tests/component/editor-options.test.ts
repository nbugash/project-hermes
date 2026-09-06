import { describe, expect, it } from 'vitest';
import { largeFileEditorOptions } from '../../src/renderer/editor/editorOptions';

/**
 * Monaco's defaults assume ordinary files. On a 50,000-line document a few of them turn into
 * whole-file work per render, so they are set explicitly rather than left to change underneath us on
 * a Monaco upgrade.
 */
describe('largeFileEditorOptions', () => {
  const options = largeFileEditorOptions();

  it('stops rendering absurdly long lines', () => {
    // A minified or generated line can be hundreds of thousands of characters. Rendering it fully
    // blocks the frame that is trying to show the rest of the file.
    expect(options.stopRenderingLineAfter).toBeGreaterThan(0);
    expect(options.stopRenderingLineAfter).toBeLessThanOrEqual(20_000);
  });

  it('caps tokenization line length', () => {
    expect(options.maxTokenizationLineLength).toBeGreaterThan(0);
    expect(options.maxTokenizationLineLength).toBeLessThanOrEqual(20_000);
  });

  it('disables the minimap', () => {
    // The minimap renders the whole document, which is precisely the whole-file work the design
    // exists to avoid — and it is invisible at this file size anyway.
    expect(options.minimap?.enabled).toBe(false);
  });

  it('leaves semantic highlighting on, since it is the whole point', () => {
    expect(options['semanticHighlighting.enabled']).toBe(true);
  });

  it('does not scroll beyond the last line', () => {
    expect(options.scrollBeyondLastLine).toBe(false);
  });

  it('keeps automatic layout on so the editor fills its container', () => {
    // The container is flex-sized; without this Monaco measures once and never notices a resize.
    expect(options.automaticLayout).toBe(true);
  });
});
