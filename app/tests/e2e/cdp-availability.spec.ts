import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));

/**
 * T068: confirms Playwright can open a CDP session against an Electron-launched context.
 *
 * The scroll measurement depends on it twice over — `gpuBenchmarking.smoothScrollBy` to drive
 * scrolling the way the compositor sees it, and CDP frame metrics to count dropped frames. Research
 * flagged this as unverified against Electron specifically, so it is checked on its own before any
 * test is built on top of it.
 */
let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

test('a CDP session can be opened against an Electron page', async () => {
  app = await electron.launch({
    args: [
      path.join(appRoot, 'dist/main/main.js'),
      '--no-sandbox',
      '--enable-gpu-benchmarking',
      '--enable-threaded-compositing',
    ],
    env: { ...process.env, VEGA_BACKEND_COMMAND: 'sleep', VEGA_BACKEND_ARGS: '30' },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="open-file"]');

  const context = window.context();
  const session = await context.newCDPSession(window);

  const version = await session.send('Browser.getVersion');
  expect(version.product).toMatch(/Chrome|Electron/i);

  await session.detach();
});

test('gpuBenchmarking.smoothScrollBy is exposed under --enable-gpu-benchmarking', async () => {
  app = await electron.launch({
    args: [
      path.join(appRoot, 'dist/main/main.js'),
      '--no-sandbox',
      '--enable-gpu-benchmarking',
      '--enable-threaded-compositing',
    ],
    env: { ...process.env, VEGA_BACKEND_COMMAND: 'sleep', VEGA_BACKEND_ARGS: '30' },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="open-file"]');

  // rAF deltas are blind to compositor work, which is where scroll jank actually lives (research
  // D7). smoothScrollBy drives the real scroll pipeline instead of synthesising wheel events.
  const available = await window.evaluate(() => {
    const bench = (globalThis as Record<string, any>).chrome?.gpuBenchmarking;
    return {
      present: typeof bench === 'object' && bench !== null,
      hasSmoothScroll: typeof bench?.smoothScrollBy === 'function',
    };
  });

  expect(available.present).toBe(true);
  expect(available.hasSmoothScroll).toBe(true);
});
