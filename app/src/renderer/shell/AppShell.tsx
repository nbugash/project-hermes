import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import { EditorHost } from '../editor/EditorHost';
import { TimingCollector } from '../timing/collector';
import { TimingPanel } from '../timing/TimingPanel';
import { afterNextFrame } from '../timing/probe';
import { describeSaveOutcome, hashText } from '../editor/save';
import type { LoadedDocument } from '../editor/load-document';
import { StatusBar } from './StatusBar';
import { describeBackendStatus, type BackendStatus } from './backend-status';
import type { VegaBridge } from '../../preload/bridge';

declare global {
  interface Window {
    vega: VegaBridge;
  }
}

/**
 * Application shell: window chrome, backend status, and the editor.
 *
 * Renders immediately with no backend. The window must be interactive within 2 s (FR-004) while a
 * JVM start and a 50,000-line parse take longer than that, so "not ready yet" is a normal state to
 * render rather than an error to wait out.
 */
export function AppShell({ bridge = window.vega }: { bridge?: VegaBridge }) {
  const [status, setStatus] = useState<BackendStatus>('starting');
  const [detail, setDetail] = useState<string | undefined>();
  const [uri, setUri] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reloadSignal, setReloadSignal] = useState(0);
  const [timingOpen, setTimingOpen] = useState(false);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);
  // Mirrored into state as well as the ref: the ref serves save without re-rendering per keystroke,
  // while the status bar needs to actually repaint when a document loads.
  const [openDocument, setOpenDocument] = useState<LoadedDocument | null>(null);
  const [lineCount, setLineCount] = useState(0);
  const readText = useRef<() => string>(() => '');
  const loaded = useRef<LoadedDocument | null>(null);

  // One collector for the session. Recreating it per render would reset the rolling window on every
  // keystroke, so the panel would only ever show the most recent interaction.
  const collector = useMemo(() => new TimingCollector(), []);

  // The collector mutates in place, so the panel needs a nudge to repaint. A counter is cheaper than
  // copying the sample array into state on every keystroke.
  const [, repaintTiming] = useReducer((count: number) => count + 1, 0);

  useEffect(() => {
    bridge.onBackendStatus((next, nextDetail) => {
      setStatus(next);
      setDetail(nextDetail);
      if (next === 'ready') {
        // A document requested before the backend finished starting failed to load. Now that it can
        // be served, ask again rather than leaving the window permanently empty.
        setError(null);
        setReloadSignal((signal) => signal + 1);
      }
    });
    // Asked for once on mount: a backend that failed to spawn reports it within milliseconds, well
    // before the listener above exists, and that push would otherwise be lost.
    void bridge.backendStatus().then((current) => {
      setStatus(current.status);
      setDetail(current.detail);
    });

    void bridge.initialFile().then((opened) => {
      if (opened !== null) {
        setUri(opened);
      }
    });
  }, [bridge]);

  useEffect(() => {
    // Measured on every keystroke regardless of whether the panel is open: opening it should show
    // history, not start collecting from scratch. The cost is one rAF callback per keystroke.
    const onKeyDown = (event: KeyboardEvent) => {
      const started = event.timeStamp;
      afterNextFrame(() => {
        collector.record({
          kind: 'keystroke',
          inputToRenderMs: Math.max(0, performance.now() - started),
        });
        repaintTiming();
      });
    };

    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [collector]);

  const save = useCallback(async () => {
    const document = loaded.current;
    if (document === null) {
      return;
    }

    // Normalised defensively: the mirror is LF, and hashing anything else guarantees a refusal
    // that looks like a synchronisation bug rather than an encoding difference.
    const text = readText.current().replace(/\r\n/g, '\n');
    const saveStarted = performance.now();
    const outcome = describeSaveOutcome(
      await bridge.saveDocument({
        uri: document.uri,
        version: 0,
        // Proves the backend's mirror matches this buffer; the payload carries no text, so this
        // hash is the only thing standing between a cheap save and writing text nobody saw.
        expectedContentHash: await hashText(text),
        // Proves the file on disk is still the one that was read.
        baseContentHash: document.metadata.contentHash,
      }),
    );

    collector.recordBackendRoundTrip(performance.now() - saveStarted);
    setSaveMessage(outcome.message);
  }, [bridge, collector]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key === 's') {
        event.preventDefault();
        void save();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [save]);

  const openFile = useCallback(async () => {
    const chosen = await bridge.openFileDialog();
    if (chosen) {
      setError(null);
      setUri(chosen);
    }
  }, [bridge]);

  const view = describeBackendStatus(status, detail);

  return (
    <div className="app-shell">
      <header className="app-shell__bar">
        <span className="app-shell__mark">vega</span>
        <span className="app-shell__file">{fileName(uri)}</span>

        <span className="app-shell__spacer" />

        <button type="button" onClick={() => void openFile()} data-testid="open-file">
          Open file
        </button>
        <button type="button" onClick={() => void save()} data-testid="save">
          Save
        </button>
        <button
          type="button"
          onClick={() => setTimingOpen((open) => !open)}
          data-testid="toggle-timing"
          aria-pressed={timingOpen}
        >
          Timing
        </button>

        <span
          className="app-shell__status"
          data-testid="backend-status"
          data-degraded={view.degraded}
        >
          {view.label}
        </span>
      </header>

      {error ? (
        <p className="app-shell__message" data-tone="error" role="alert" data-testid="load-error">
          {error}
        </p>
      ) : null}

      {saveMessage ? (
        <p
          className="app-shell__message"
          data-tone={saveMessage.startsWith('Saved') ? 'notice' : 'error'}
          role="status"
          data-testid="save-status"
        >
          {saveMessage}
        </p>
      ) : null}

      <main className="app-shell__editor">
        <EditorHost
          bridge={bridge}
          uri={uri}
          reloadSignal={reloadSignal}
          onError={setError}
          onLoaded={(document) => {
            loaded.current = document;
            setOpenDocument(document);
            setLineCount(document.text.split('\n').length);
          }}
          registerTextReader={(read) => {
            readText.current = read;
          }}
          onBackendRoundTrip={(milliseconds) => {
            collector.recordBackendRoundTrip(milliseconds);
            repaintTiming();
          }}
        />
        {timingOpen ? <TimingPanel collector={collector} onClose={() => setTimingOpen(false)} /> : null}
      </main>

      <StatusBar document={openDocument} lineCount={lineCount} collector={collector} />
    </div>
  );
}

/** The file's own name; the full path is not what a person scanning the title bar is looking for. */
function fileName(uri: string | null): string {
  if (uri === null) {
    return 'No file open';
  }
  return decodeURIComponent(uri.slice(uri.lastIndexOf('/') + 1));
}
