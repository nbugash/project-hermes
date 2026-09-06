import type * as monaco from 'monaco-editor';

/**
 * Editor options tuned for a 50,000-line document.
 *
 * Set explicitly rather than inherited: Monaco's defaults are chosen for ordinary files, and several
 * of them become whole-file work at this size. Pinning them here also means a Monaco upgrade cannot
 * quietly change the performance characteristics the budgets were measured against.
 */
/**
 * Editor theme matching the shell.
 *
 * Defined rather than picking `vs-dark`, so the editor surface belongs to the same enclosure as the
 * chrome around it — a default Monaco theme reads as a component someone dropped in. Amber carries
 * through from the interface mark to keywords, which is the one place syntax and identity meet;
 * everything else stays cool so the code is read by shape rather than by colour.
 */
export const VEGA_THEME = 'vega';

export function defineVegaTheme(monaco: typeof import('monaco-editor')): void {
  monaco.editor.defineTheme(VEGA_THEME, {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword', foreground: 'e8b04b' },
      { token: 'type', foreground: '7fb3d5' },
      { token: 'function', foreground: '9dd6c0' },
      { token: 'variable', foreground: 'd8e0ea' },
      { token: 'string', foreground: 'dcc08a' },
      { token: 'number', foreground: 'e0a3a3' },
      { token: 'comment', foreground: '5c6b7d', fontStyle: 'italic' },
      { token: 'operator', foreground: '8494a6' },
    ],
    colors: {
      'editor.background': '#10151c',
      'editor.foreground': '#d8e0ea',
      'editorLineNumber.foreground': '#46566a',
      'editorLineNumber.activeForeground': '#8494a6',
      'editor.lineHighlightBackground': '#161d26',
      'editor.selectionBackground': '#25415c',
      'editorCursor.foreground': '#e8b04b',
      'editorIndentGuide.background1': '#1c2530',
      'editorIndentGuide.activeBackground1': '#2f3d4d',
      'editorWidget.background': '#161d26',
      'editorWidget.border': '#232d3a',
      'scrollbarSlider.background': '#232d3a80',
      'scrollbarSlider.hoverBackground': '#2f3d4db0',
    },
  });
}

export function largeFileEditorOptions(): monaco.editor.IStandaloneEditorConstructionOptions {
  return {
    language: 'java',
    automaticLayout: true,
    theme: VEGA_THEME,

    // Plex Mono matches the interface face, so a file name in the title bar and the code beneath it
    // are visibly the same family.
    fontFamily: "'IBM Plex Mono', ui-monospace, monospace",
    fontSize: 13,
    lineHeight: 1.65,
    padding: { top: 12 },
    renderLineHighlight: 'line',
    smoothScrolling: false,

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
