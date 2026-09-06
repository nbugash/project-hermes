import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import EditorWorker from 'monaco-editor/editor/editor.worker.js?worker';
import { AppShell } from './shell/AppShell';
import './styles.css';

/**
 * Monaco resolves its workers through this global, and a bundler has to be told how to build them.
 *
 * Left unset, Monaco warns once and then runs tokenization on the main thread — the same thread that
 * has to service keystrokes. The editor still works, so this is invisible until someone measures
 * input latency and finds the budget missed for no visible reason.
 *
 * Only the base editor worker is registered: the app edits Java, whose highlighting comes from the
 * backend, so Monaco's own language services are not in use.
 *
 * The specifier goes through Monaco 0.56's `exports` map (`./*.js` -> `./esm/vs/*.js`). The deep
 * `monaco-editor/esm/vs/...` path that older guides use is no longer resolvable, and the failure is
 * a build error rather than a runtime one — which is how a stale bundle can keep working while the
 * fix silently never ships.
 */
self.MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};

/**
 * Renderer entry point.
 *
 * Mounts synchronously and without awaiting the backend: the window has to be interactive within the
 * cold-start budget, and the backend is reported as a status rather than waited for.
 */
const container = document.getElementById('root');
if (!container) {
  throw new Error('Renderer root element is missing from index.html');
}

createRoot(container).render(
  <StrictMode>
    <AppShell />
  </StrictMode>,
);
