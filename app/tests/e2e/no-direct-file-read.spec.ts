import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));

/**
 * FR-002 / FR-021: the editor process performs no filesystem read of the opened document.
 *
 * Asserting "it did not read the file" directly is not possible from outside the process, so this
 * asserts the stronger structural property that makes it impossible: the renderer has no filesystem
 * API at all, and its only route outward is the enumerated bridge. A renderer that cannot reach the
 * filesystem cannot read the document from it.
 */
let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

test('renderer has no filesystem access of any kind', async () => {
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    env: { ...process.env, VEGA_BACKEND_COMMAND: 'sleep', VEGA_BACKEND_ARGS: '30' },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="open-file"]');

  const capabilities = await window.evaluate(() => ({
    hasRequire: typeof (globalThis as Record<string, unknown>).require !== 'undefined',
    hasProcess: typeof (globalThis as Record<string, unknown>).process !== 'undefined',
    hasModule: typeof (globalThis as Record<string, unknown>).module !== 'undefined',
    bridgeKeys: Object.keys((globalThis as Record<string, unknown>).vega ?? {}).sort(),
  }));

  expect(capabilities.hasRequire).toBe(false);
  expect(capabilities.hasProcess).toBe(false);
  expect(capabilities.hasModule).toBe(false);

  // The bridge is an allow-list. A new entry here is a deliberate widening of what UI code can do,
  // and should have to be argued for in review rather than appearing by accident.
  expect(capabilities.bridgeKeys).toEqual([
    'backendStatus',
    'changeDocument',
    'initialFile',
    'onBackendStatus',
    'openDocument',
    'openFileDialog',
    'readDocument',
    'saveDocument',
    'semanticTokens',
  ]);
});

test('document contents arrive through the bridge, not the filesystem', async () => {
  // A backend that cannot start, rather than one that is merely slow: the point is that the read
  // fails instead of quietly falling back to reading the file from the renderer.
  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox'],
    env: {
      ...process.env,
      VEGA_BACKEND_COMMAND: 'definitely-not-a-real-backend',
      VEGA_BACKEND_ARGS: '',
    },
  });

  const window = await app.firstWindow();
  await window.waitForSelector('[data-testid="open-file"]');

  // With no backend connected the read must fail rather than fall back to reading the file locally.
  // A silent local fallback would pass every functional test and violate the constraint entirely.
  const outcome = await window.evaluate(async () => {
    try {
      await (globalThis as Record<string, any>).vega.readDocument('file:///nonexistent/A.java');
      return 'resolved';
    } catch (error) {
      return error instanceof Error ? error.message : String(error);
    }
  });

  expect(outcome).not.toBe('resolved');
  expect(outcome).toMatch(/backend/i);
});
