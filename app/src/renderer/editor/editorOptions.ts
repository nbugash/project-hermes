import type * as monaco from 'monaco-editor';

/**
 * Editor options tuned for a 50,000-line document.
 *
 * Set explicitly rather than inherited: Monaco's defaults are chosen for ordinary files, and several
 * of them become whole-file work at this size. Pinning them here also means a Monaco upgrade cannot
 * quietly change the performance characteristics the budgets were measured against.
 */
export function largeFileEditorOptions(): monaco.editor.IStandaloneEditorConstructionOptions {
  return {
    language: 'java',
    automaticLayout: true,

    // Semantic highlighting comes from the backend; this is the feature the spike exists to deliver.
    'semanticHighlighting.enabled': true,

    // Generated and minified Java can carry lines of a hundred thousand characters. Rendering one
    // fully blocks the frame that is trying to paint the rest of the viewport, so it is truncated
    // for display — the text is untouched, only its rendering stops.
    stopRenderingLineAfter: 10_000,

    // Same reasoning for tokenization: past this length a line is not worth colouring, and
    // attempting it costs the frame.
    maxTokenizationLineLength: 10_000,

    // The minimap renders the entire document to paint a thumbnail — exactly the whole-file work
    // Principle VI rejects, and unreadable at 50,000 lines regardless.
    minimap: { enabled: false },

    scrollBeyondLastLine: false,
  };
}
