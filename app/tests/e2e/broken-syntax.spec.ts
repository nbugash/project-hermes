import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));
const repoRoot = path.dirname(appRoot);
const backend = path.join(repoRoot, 'server/app/build/install/app/bin/app');
const fixture = path.join(repoRoot, 'fixtures/large-java-file/RealCorpus.java');

/**
 * SC-006 from the user's side: while the file is syntactically invalid the editor keeps working, and
 * fixing the syntax restores it.
 *
 * A developer spends much of their time in briefly-invalid code — every unclosed brace between
 * typing it and closing it. If responsiveness degraded there, it would degrade during exactly the
 * stretch of editing where it is most noticeable, and every measurement taken on valid code would
 * have missed it.
 */
const enforceBudget = process.env.VEGA_PERF_GATE === '1';

test.setTimeout(240_000);

let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

async function launchWithDocument() {
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
  return window;
}

async function measureTyping(window: Awaited<ReturnType<typeof launchWithDocument>>, count: number) {
  await window.evaluate(() => {
    const state = { latencies: [] as number[], longFrames: 0 };
    (globalThis as Record<string, unknown>).__vegaBroken = state;
    document.addEventListener('keydown', (event) => {
      const started = event.timeStamp;
      requestAnimationFrame(() => {
        const channel = new MessageChannel();
        channel.port1.onmessage = () => state.latencies.push(performance.now() - started);
        channel.port2.postMessage(undefined);
      });
    });
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

  for (let i = 0; i < count; i++) {
    await window.keyboard.press('x');
  }
  await window.waitForTimeout(300);

  return window.evaluate(() => {
    const state = (globalThis as Record<string, any>).__vegaBroken;
    const sorted = [...state.latencies].sort((a: number, b: number) => a - b);
    return {
      samples: sorted.length,
      p95: sorted[Math.floor(sorted.length * 0.95)] ?? 0,
      longFrames: state.longFrames as number,
    };
  });
}

test('@perf typing stays responsive while the document is syntactically invalid', async () => {
  const window = await launchWithDocument();
  await window.click('.monaco-editor');
  await window.keyboard.press('Control+Home');

  // An unmatched closing brace: unambiguously invalid, unlike a stray opening brace, which
  // tree-sitter recovers as a block whose closer is merely missing.
  await window.keyboard.press('}');
  await window.waitForTimeout(500);

  const measured = await measureTyping(window, 40);

  console.log(`typing while broken: p95 ${measured.p95.toFixed(2)}ms long frames ${measured.longFrames}`);
  expect(measured.samples).toBeGreaterThan(20);
  expect(measured.longFrames).toBeLessThanOrEqual(1);
  if (enforceBudget) {
    expect(measured.p95).toBeLessThan(16);
  }
});

test('the document still renders styled text while invalid, and recovers when fixed', async () => {
  const window = await launchWithDocument();
  await window.click('.monaco-editor');
  await window.keyboard.press('Control+Home');

  const styledSpans = () =>
    window.evaluate(
      () =>
        Array.from(document.querySelectorAll('.view-line')).filter(
          (line) => line.querySelectorAll('span').length > 1,
        ).length,
    );

  const before = await styledSpans();
  expect(before).toBeGreaterThan(0);

  await window.keyboard.press('}');
  await window.waitForTimeout(1500);

  // Degraded, never blank. A parser that gave up would leave the viewport unstyled.
  expect(await styledSpans()).toBeGreaterThan(0);

  await window.keyboard.press('Backspace');
  await window.waitForTimeout(1500);

  // Recovery must be complete rather than approximate; damage that accumulates across an editing
  // session would leave the file progressively less styled the longer it is worked on.
  expect(await styledSpans()).toBeGreaterThanOrEqual(before);
});
