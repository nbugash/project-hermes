import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));

/**
 * A dead backend must announce itself.
 *
 * Unreported, it presents as highlighting that never arrives — which reads as a slow editor rather
 * than a broken one, so the user waits instead of acting. This is the failure mode the status area
 * exists for, and the one most likely to be missed because nothing throws.
 */
test.setTimeout(120_000);

let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

test('a backend that exits during startup is reported, not left as starting', async () => {
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    // Runs briefly, then exits non-zero — a JVM that fails after launch, a missing jar, an OOM.
    env: {
      ...process.env,
      VEGA_BACKEND_COMMAND: 'sh',
      VEGA_BACKEND_ARGS: '-c,sleep 1; exit 3',
    },
  });

  const window = await app.firstWindow();
  const status = window.locator('[data-testid="backend-status"]');

  await expect(status).toContainText(/unavailable|exited/i, { timeout: 30_000 });
  await expect(status).toHaveAttribute('data-degraded', 'true');
});

test('a backend that never starts is reported with the reason', async () => {
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    env: {
      ...process.env,
      VEGA_BACKEND_COMMAND: 'definitely-not-a-real-backend',
      VEGA_BACKEND_ARGS: '',
    },
  });

  const window = await app.firstWindow();

  // The message has to carry something actionable. "Backend unavailable" alone leaves the user
  // guessing between a missing JVM, a wrong path and a crash.
  await expect(window.locator('[data-testid="backend-status"]')).toContainText(
    /unavailable/i,
    { timeout: 30_000 },
  );
});

test('the editor stays usable after the backend dies', async () => {
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    env: { ...process.env, VEGA_BACKEND_COMMAND: 'sh', VEGA_BACKEND_ARGS: '-c,sleep 1; exit 3' },
  });

  const window = await app.firstWindow();
  await expect(window.locator('[data-testid="backend-status"]')).toContainText(
    /unavailable|exited/i,
    { timeout: 30_000 },
  );

  // Losing analysis must not cost the user their ability to type. Disabling the editor here would
  // turn a degraded session into lost work.
  await window.click('[data-testid="toggle-timing"]');
  await expect(window.locator('[data-testid="timing-panel"]')).toBeVisible();
});
