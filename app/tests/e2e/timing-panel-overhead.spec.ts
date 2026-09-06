import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));
const repoRoot = path.dirname(appRoot);
const backend = path.join(repoRoot, 'server/app/build/install/app/bin/app');
const fixture = path.join(repoRoot, 'fixtures/large-java-file/RealCorpus.java');

/**
 * The instrument must not cost what it measures.
 *
 * A panel that shipped but degraded typing would be self-defeating: its own figures would include
 * its overhead, and a budget miss would be ambiguous between the editor and the instrument. So the
 * panel is opened, the document is typed into, and the panel's own reported figure is read back —
 * which also proves the collection path works end to end, not just in unit tests.
 */
const enforceBudget = process.env.VEGA_PERF_GATE === '1';

test.setTimeout(240_000);

let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

test('@perf keystroke latency holds with the timing panel open', async () => {
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox', fixture],
    env: { ...process.env, VEGA_BACKEND_COMMAND: backend, VEGA_BACKEND_ARGS: '' },
  });

  const window = await app.firstWindow();
  await window.waitForFunction(
    () => document.querySelectorAll('.view-line').length > 5,
    undefined,
    { timeout: 90_000 },
  );

  // Count long frames independently of the panel, so the panel cannot report on its own overhead.
  await window.evaluate(() => {
    const state = { longFrames: 0 };
    (globalThis as Record<string, unknown>).__vegaPanelFrames = state;
    let previous = performance.now();
    const tick = (now: number) => {
      if (now - previous > 32) {
        state.longFrames++;
      }
      previous = now;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });

  await window.click('[data-testid="toggle-timing"]');
  await expect(window.locator('[data-testid="timing-panel"]')).toBeVisible();

  await window.click('.monaco-editor');
  for (let i = 0; i < 40; i++) {
    await window.keyboard.press('x');
  }
  await window.waitForTimeout(500);

  const longFrames = await window.evaluate(
    () => (globalThis as Record<string, any>).__vegaPanelFrames.longFrames as number,
  );
  const reported = await window.locator('[data-testid="input-render-p95"]').textContent();
  console.log(`panel open: reported input-to-render p95 ${reported}, long frames ${longFrames}`);

  // The panel repaints on every keystroke, so this is the assertion that matters everywhere: even
  // under software rendering, the instrument must not make the editor skip frames.
  expect(longFrames).toBeLessThanOrEqual(1);

  // It reported something, which means the collection path ran for real interactions.
  expect(reported).not.toBe('—');

  if (enforceBudget && reported !== null) {
    expect(Number.parseFloat(reported)).toBeLessThan(16);
  }
});
