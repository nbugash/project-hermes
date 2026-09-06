import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));

/**
 * SC-003: the timing panel ships. It is reachable in a production-configuration build, with no
 * developer flag, no environment variable and no hidden shortcut.
 *
 * A performance claim that can only be checked in CI is one the user has to take on trust. This test
 * exists so that "we shipped the panel" cannot quietly become "we shipped it behind a flag" — the
 * usual fate of developer tooling, and invisible in a diff.
 */
let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

test('the panel is reachable with no developer flag set', async () => {
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    // Deliberately no VEGA_DEBUG, no NODE_ENV=development, no feature flag of any kind.
    env: { ...process.env, VEGA_BACKEND_COMMAND: 'sleep', VEGA_BACKEND_ARGS: '30' },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="toggle-timing"]');

  await window.click('[data-testid="toggle-timing"]');

  const panel = window.locator('[data-testid="timing-panel"]');
  await expect(panel).toBeVisible();
  await expect(window.locator('[data-testid="input-render-p50"]')).toBeVisible();
  await expect(window.locator('[data-testid="round-trip-p50"]')).toBeVisible();
});

test('the panel reports figures after interaction and can be dismissed', async () => {
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    env: { ...process.env, VEGA_BACKEND_COMMAND: 'sleep', VEGA_BACKEND_ARGS: '30' },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="toggle-timing"]');

  await window.click('.monaco-editor');
  for (let i = 0; i < 10; i++) {
    await window.keyboard.press('x');
  }

  await window.click('[data-testid="toggle-timing"]');
  await expect(window.locator('[data-testid="timing-panel-scope"]')).toContainText('keystroke');

  // An em dash means "nothing reached the backend", which is the honest reading with no backend
  // connected — and a different statement from the backend being instantaneous.
  await expect(window.locator('[data-testid="round-trip-p50"]')).toHaveText('—');

  await window.click('[data-testid="timing-panel-close"]');
  await expect(window.locator('[data-testid="timing-panel"]')).toHaveCount(0);
});
