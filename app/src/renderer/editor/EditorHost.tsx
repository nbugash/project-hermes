import { useEffect, useRef, useState } from 'react';
import * as monaco from 'monaco-editor';
import { VegaSemanticTokensProvider } from './SemanticTokensProvider';
import { defineVegaTheme, largeFileEditorOptions } from './editorOptions';
import { loadDocument, type LoadedDocument } from './load-document';
import type { VegaBridge } from '../../preload/bridge';

/**
 * Token legend, in the order the server declared it.
 *
 * Order is the wire encoding: a token's type is an index into this array, so a mismatch does not
 * fail — it silently paints every token as the wrong kind.
 */
const TOKEN_TYPES = ['keyword', 'type', 'function', 'variable', 'string', 'number', 'comment', 'operator'];
const TOKEN_MODIFIERS = ['declaration', 'static', 'readonly'];

/**
 * Hosts the Monaco editor and loads documents through the backend bridge.
 *
 * Monaco is created once and its model swapped per document. Disposing and recreating the editor per
 * open is the obvious alternative and the wrong one: on a 50,000-line file it re-runs the whole
 * viewport setup that the cold-start budget is trying to protect.
 */
export function EditorHost({
  bridge,
  uri,
  reloadSignal = 0,
  onLoaded,
  onError,
  registerTextReader,
}: {
  bridge: VegaBridge;
  uri: string | null;
  /**
   * Changes to re-attempt a load that failed. A file named on the command line is handed to the
   * renderer as soon as the page loads, which is deliberately before the backend is ready — so the
   * first read can legitimately fail, and without a retry the document never appears at all.
   */
  reloadSignal?: number;
  onLoaded?: (document: LoadedDocument) => void;
  onError?: (message: string) => void;
  /**
   * Hands the shell a way to read the current buffer.
   *
   * The model belongs to Monaco, so mirroring its text into React state would copy the whole
   * document on every keystroke — the O(file size) per-keystroke cost the design exists to avoid.
   * A reader lets save pull the text once, when it is actually needed.
   */
  registerTextReader?: (read: () => string) => void;
}) {
  // Callbacks are held in refs and deliberately kept out of the effect dependencies below. Passed
  // as inline arrows by the caller they get a fresh identity on every render, and the shell
  // re-renders on every keystroke to update the timing panel. Depending on them re-ran the load
  // effect per character — re-reading the file and resetting the backend's mirror mid-edit, which
  // surfaced only as a refused save much later.
  const onLoadedRef = useRef(onLoaded);
  const onErrorRef = useRef(onError);
  onLoadedRef.current = onLoaded;
  onErrorRef.current = onError;

  const container = useRef<HTMLDivElement | null>(null);
  const editor = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const changeSubscription = useRef<monaco.IDisposable | null>(null);
  const tokensProvider = useRef<monaco.IDisposable | null>(null);
  const version = useRef(0);
  const [loading, setLoading] = useState(false);

  const registerTextReaderRef = useRef(registerTextReader);
  registerTextReaderRef.current = registerTextReader;

  useEffect(() => {
    registerTextReaderRef.current?.(() => editor.current?.getModel()?.getValue() ?? '');
  }, []);

  useEffect(() => {
    if (!container.current || editor.current) {
      return;
    }
    // Defined before create, or the editor paints once with a default theme and visibly restyles.
    defineVegaTheme(monaco);

    // Options live in one place so the large-file guards are visible and testable rather than
    // scattered through the component that happens to construct the editor.
    editor.current = monaco.editor.create(container.current, largeFileEditorOptions());

    // Registered once for the language, not per document: Monaco keys providers by language, and
    // registering per open would stack duplicates that each answer the same request.
    tokensProvider.current = monaco.languages.registerDocumentSemanticTokensProvider(
      'java',
      new VegaSemanticTokensProvider(
        {
          requestTokens: (uri, previousResultId) =>
            bridge.semanticTokens(uri, previousResultId) as Promise<never>,
          documentVersion: () => version.current,
        },
        { tokenTypes: TOKEN_TYPES, tokenModifiers: TOKEN_MODIFIERS },
      ),
    );

    return () => {
      changeSubscription.current?.dispose();
      tokensProvider.current?.dispose();
      editor.current?.getModel()?.dispose();
      editor.current?.dispose();
      editor.current = null;
    };
  }, [bridge]);

  useEffect(() => {
    if (!uri || !editor.current) {
      return;
    }

    let cancelled = false;
    setLoading(true);

    loadDocument(bridge, uri)
      .then((document) => {
        // A slower earlier load must not overwrite a newer one; without this guard, opening two
        // files quickly can leave the second one's title over the first one's text.
        if (cancelled || !editor.current) {
          return;
        }
        const model = monaco.editor.createModel(document.text, 'java', monaco.Uri.parse(uri));
        // The backend's mirror is LF-normalised and the original line-ending style is restored on
        // save from the file's metadata. Letting the model default to the platform EOL would make
        // getValue() return CRLF on Windows, so the buffer hash would never match the mirror and
        // every save would be refused as a mismatch.
        model.setEOL(monaco.editor.EndOfLineSequence.LF);
        const previous = editor.current.getModel();
        editor.current.setModel(model);
        previous?.dispose();

        // The backend does not learn about a document from the read that fetched its text — reading
        // is deliberately not opening (it must not parse). didOpen is what starts analysis.
        version.current = 1;
        void bridge.openDocument(uri, document.text, version.current);

        // Every subsequent edit is forwarded as a range change. Without this the backend's mirror
        // silently stops matching the buffer, and both highlighting and save quietly break — save
        // by refusing, highlighting by describing text the user no longer has.
        changeSubscription.current?.dispose();
        changeSubscription.current = model.onDidChangeContent((event) => {
          version.current += 1;
          void bridge.changeDocument(
            uri,
            version.current,
            // LSP positions are zero-based; Monaco's are one-based. Off by one here would apply
            // every edit one line and one column early, corrupting the mirror silently.
            event.changes.map((change) => ({
              range: {
                start: {
                  line: change.range.startLineNumber - 1,
                  character: change.range.startColumn - 1,
                },
                end: {
                  line: change.range.endLineNumber - 1,
                  character: change.range.endColumn - 1,
                },
              },
              text: change.text,
            })),
          );
        });

        onLoadedRef.current?.(document);
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          onErrorRef.current?.(error instanceof Error ? error.message : String(error));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [bridge, uri, reloadSignal]);

  return (
    <div className="editor-host" data-testid="editor-host" data-loading={loading}>
      <div ref={container} className="editor-surface" data-testid="editor-surface" />
    </div>
  );
}
