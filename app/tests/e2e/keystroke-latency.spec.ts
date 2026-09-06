import { test, expect, _electron as electron, type ElectronApplication, type Page } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));
const repoRoot = path.dirname(appRoot);
const backend = path.join(repoRoot, 'server/app/build/install/app/bin/app');
const fixture = path.join(repoRoot, 'fixtures/large-java-file/RealCorpus.java');

/**
 * SC-001a: keystroke to committed frame, p95 under 16 ms, at most one dropped frame.
 *
 * Every figure here comes from in-page instrumentation, never from Playwright's clock: each driver
 * action is an IPC round trip whose overhead is the same order of magnitude as the budget being
 * measured (ADR-0001). Playwright drives the keyboard; the page times itself.
 *
 * Runs against the real backend and the real 50,000-line corpus. A latency test on a small file, or
 * against a stub, measures nothing anyone cares about.
 */
// Backend start, document read and a 50,000-line render happen before any measurement begins. This
// is harness headroom, not a performance budget — the budgets are asserted from in-page timings.
test.setTimeout(180_000);

/**
 * Whether to hold the measurement to the budget.
 *
 * Under xvfb there is no GPU: Chromium falls back to software compositing, which inflates frame
 * times well past what the same code does on a real display. Asserting the budget here would
 * manufacture a failure that says nothing about the product; loosening the budget so it passes
 * anyway would be worse, because it would quietly redefine what SC-001a means. So the measurement
 * always runs and is always reported, and the budget is enforced only where the number is
 * meaningful — set VEGA_PERF_GATE=1 on GPU-accelerated hardware.
 */
const enforceBudget = process.env.VEGA_PERF_GATE === '1';

let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

async function launchWithDocument(): Promise<Page> {
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox', fixture],
    env: { ...process.env, VEGA_BACKEND_COMMAND: backend, VEGA_BACKEND_ARGS: '' },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="editor-surface"]', { timeout: 20_000 });

  // Wait for the document itself, not merely for the editor shell: typing into an empty buffer
  // would report a latency that has nothing to do with a 50,000-line file.
  await window.waitForFunction(
    () => (document.querySelectorAll('.view-line').length ?? 0) > 5,
    undefined,
    { timeout: 60_000 },
  );
  return window;
}

/** Installs an input-to-frame probe using the technique in src/renderer/timing/probe.ts. */
async function installProbe(window: Page): Promise<void> {
  await window.evaluate(() => {
    const state = { latencies: [] as number[], longFrames: 0 };
    (globalThis as Record<string, unknown>).__vegaProbe = state;

    // Monaco 0.56 drives input through the EditContext API and renders no input textarea — only an
    // aria-hidden IME buffer. Listening for keydown on the document is both what works here and the
    // more faithful start point for SC-001a, which is measured from the keystroke itself.
    document.addEventListener('keydown', (event) => {
      const started = event.timeStamp;
      requestAnimationFrame(() => {
        // A MessageChannel task runs after the frame has been produced; setTimeout(0) does not,
        // because Chromium clamps nested timers and does not order them after the render steps.
        const channel = new MessageChannel();
        channel.port1.onmessage = () => state.latencies.push(performance.now() - started);
        channel.port2.postMessage(undefined);
      });
    });

    let previousFrame = performance.now();
    const tick = (now: number) => {
      if (now - previousFrame > 32) {
        state.longFrames++;
      }
      previousFrame = now;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
}

async function typeAndMeasure(window: Page, characters: number) {
  await window.evaluate(() => {
    const state = (globalThis as Record<string, any>).__vegaProbe;
    state.latencies.length = 0;
    state.longFrames = 0;
  });

  for (let i = 0; i < characters; i++) {
    await window.keyboard.press('x');
  }
  await window.waitForTimeout(300);

  return window.evaluate(() => {
    const state = (globalThis as Record<string, any>).__vegaProbe;
    const sorted = [...state.latencies].sort((a: number, b: number) => a - b);
    return {
      samples: sorted.length,
      p50: sorted[Math.floor(sorted.length * 0.5)] ?? 0,
      p95: sorted[Math.floor(sorted.length * 0.95)] ?? 0,
      longFrames: state.longFrames as number,
    };
  });
}

test('@perf keystroke latency stays within budget at the top of the file', async () => {
  const window = await launchWithDocument();
  await installProbe(window);

  await window.click('.monaco-editor');
  await window.keyboard.press('Control+Home');

  const measured = await typeAndMeasure(window, 40);

  expect(measured.samples).toBeGreaterThan(20);
  console.log(`line 1: p50 ${measured.p50.toFixed(2)}ms p95 ${measured.p95.toFixed(2)}ms long frames ${measured.longFrames}`);

  // Dropped frames are asserted everywhere: software rendering makes each frame slower, but it does
  // not make the editor skip them, so this part of SC-001a is meaningful in either environment.
  expect(measured.longFrames).toBeLessThanOrEqual(1);
  if (enforceBudget) {
    expect(measured.p95).toBeLessThan(16);
  }
});

test('@perf keystroke latency stays within budget at the end of the file', async () => {
  const window = await launchWithDocument();
  await installProbe(window);

  await window.click('.monaco-editor');
  // The far end of a 50,000-line file: the position a whole-file design punishes most.
  await window.keyboard.press('Control+End');

  const measured = await typeAndMeasure(window, 40);

  expect(measured.samples).toBeGreaterThan(20);
  console.log(`last line: p50 ${measured.p50.toFixed(2)}ms p95 ${measured.p95.toFixed(2)}ms long frames ${measured.longFrames}`);

  expect(measured.longFrames).toBeLessThanOrEqual(1);
  if (enforceBudget) {
    expect(measured.p95).toBeLessThan(16);
  }
});
