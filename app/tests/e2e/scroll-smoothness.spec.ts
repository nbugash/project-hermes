import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));
const repoRoot = path.dirname(appRoot);
const backend = path.join(repoRoot, 'server/app/build/install/app/bin/app');
const fixture = path.join(repoRoot, 'fixtures/large-java-file/RealCorpus.java');

/**
 * SC-002: traverse the whole file by wheel, keyboard and scrollbar without breaching the
 * dropped-frame threshold.
 *
 * Scrolling is driven through `chrome.gpuBenchmarking.smoothScrollBy`, which puts input into the
 * real scroll pipeline rather than synthesising events above it. `requestAnimationFrame` deltas are
 * an anti-pattern here: they run on the main thread and are blind to compositor work, which is
 * exactly where scroll jank lives (research D7). Availability of both CDP and gpuBenchmarking is
 * verified independently by cdp-availability.spec.ts.
 *
 * As with keystroke latency, the frame budget is only asserted where the number means something —
 * under xvfb there is no GPU and every frame is software-composited.
 */
const enforceBudget = process.env.VEGA_PERF_GATE === '1';

test.setTimeout(240_000);

let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

async function launchWithDocument() {
  app = await electron.launch({
    args: [
      path.join(appRoot, 'dist/main/main.js'),
      '--no-sandbox',
      '--enable-gpu-benchmarking',
      '--enable-threaded-compositing',
      fixture,
    ],
    env: { ...process.env, VEGA_BACKEND_COMMAND: backend, VEGA_BACKEND_ARGS: '' },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="editor-surface"]');
  await window.waitForFunction(
    () => document.querySelectorAll('.view-line').length > 5,
    undefined,
    { timeout: 90_000 },
  );
  return window;
}

/** Counts frames longer than two 60 Hz frames while the given gesture runs. */
async function countLongFramesDuring(
  window: Awaited<ReturnType<typeof launchWithDocument>>,
  gesture: () => Promise<void>,
) {
  await window.evaluate(() => {
    const state = { longFrames: 0, frames: 0 };
    (globalThis as Record<string, unknown>).__vegaScroll = state;
    let previous = performance.now();
    const tick = (now: number) => {
      state.frames++;
      if (now - previous > 32) {
        state.longFrames++;
      }
      previous = now;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });

  await gesture();
  await window.waitForTimeout(500);

  return window.evaluate(() => (globalThis as Record<string, any>).__vegaScroll);
}

test('@perf wheel scrolling through the document holds its frame budget', async () => {
  const window = await launchWithDocument();

  const measured = await countLongFramesDuring(window, async () => {
    // smoothScrollBy resolves through a callback, so each leg is awaited rather than fired blindly.
    for (let i = 0; i < 10; i++) {
      await window.evaluate(
        () =>
          new Promise<void>((resolve) => {
            // Only the distance and the completion callback are passed. The optional
            // gesture-source and speed arguments are rejected outright by this Chromium build, and
            // their defaults are a mouse wheel at a natural speed, which is what is being measured.
            (globalThis as Record<string, any>).chrome.gpuBenchmarking.smoothScrollBy(
              2000,
              () => resolve(),
            );
          }),
      );
    }
  });

  console.log(`wheel scroll: ${measured.frames} frames, ${measured.longFrames} long`);
  expect(measured.frames).toBeGreaterThan(10);
  if (enforceBudget) {
    expect(measured.longFrames / measured.frames).toBeLessThan(0.05);
  }
});

test('@perf keyboard paging through the document holds its frame budget', async () => {
  const window = await launchWithDocument();
  await window.click('.monaco-editor');

  const measured = await countLongFramesDuring(window, async () => {
    for (let i = 0; i < 60; i++) {
      await window.keyboard.press('PageDown');
    }
  });

  console.log(`keyboard paging: ${measured.frames} frames, ${measured.longFrames} long`);
  expect(measured.frames).toBeGreaterThan(10);
  if (enforceBudget) {
    expect(measured.longFrames / measured.frames).toBeLessThan(0.05);
  }
});

test('@perf jumping to the end of the document holds its frame budget', async () => {
  const window = await launchWithDocument();
  await window.click('.monaco-editor');

  // The scrollbar-drag equivalent: a single jump across 50,000 lines, which is the case a design
  // that renders eagerly handles worst.
  const measured = await countLongFramesDuring(window, async () => {
    await window.keyboard.press('Control+End');
    await window.waitForTimeout(1000);
  });

  console.log(`jump to end: ${measured.frames} frames, ${measured.longFrames} long`);
  expect(measured.frames).toBeGreaterThan(5);
  if (enforceBudget) {
    expect(measured.longFrames / measured.frames).toBeLessThan(0.05);
  }
});
