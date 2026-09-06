import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));

/**
 * SC-005b / FR-004: the window must be interactive before the backend is ready.
 *
 * The backend is deliberately not started here — `VEGA_BACKEND_COMMAND` points at a command that
 * never speaks LSP — because "interactive while the backend is still coming up" is precisely the
 * state under test. If these assertions needed a working backend they would be testing something
 * else.
 */
let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

test('window is interactive before the backend is ready', async () => {
  const launchedAt = Date.now();
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    env: { ...process.env, VEGA_BACKEND_COMMAND: 'sleep', VEGA_BACKEND_ARGS: '30' },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="open-file"]', { timeout: 5000 });
  const interactiveAfterMs = Date.now() - launchedAt;

  // Driver wall-clock is acceptable here and nowhere else: a 2 s budget is two orders of magnitude
  // above Playwright's IPC overhead, unlike the 16 ms frame budget in keystroke-latency.spec.ts.
  expect(interactiveAfterMs).toBeLessThan(2000);

  // Interactive means it responds, not merely that it painted.
  await window.click('[data-testid="open-file"]', { trial: true });
});

test('backend status is shown as degraded rather than hidden while starting', async () => {
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    env: { ...process.env, VEGA_BACKEND_COMMAND: 'sleep', VEGA_BACKEND_ARGS: '30' },
  });

  const window = await app.firstWindow();
  const status = window.locator('[data-testid="backend-status"]');

  await expect(status).toBeVisible();
  await expect(status).toHaveAttribute('data-degraded', 'true');
});

test('the background full-token request does not delay first paint', async () => {
  // T067. The viewport request paints first and the full-document request follows it (T064). If the
  // ordering ever inverted, cold start would wait on whole-file tokenization — the exact cost the
  // range-first design exists to keep off this path — and the only visible symptom would be a
  // slower start, with everything still correct.
  const launchedAt = Date.now();
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    env: { ...process.env, VEGA_BACKEND_COMMAND: 'sleep', VEGA_BACKEND_ARGS: '30' },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="editor-surface"]');

  expect(Date.now() - launchedAt).toBeLessThan(2000);
});
