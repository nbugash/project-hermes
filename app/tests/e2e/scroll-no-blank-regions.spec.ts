import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));
const repoRoot = path.dirname(appRoot);
const backend = path.join(repoRoot, 'server/app/build/install/app/bin/app');
const fixture = path.join(repoRoot, 'fixtures/large-java-file/RealCorpus.java');

/**
 * No blank or unstyled region in parts of the file the user has never visited.
 *
 * The viewport request covers what is on screen at open; everything else is filled by the background
 * full-document request (T064). This test scrolls into regions no viewport request ever covered,
 * which is precisely where a viewport-only design shows plain black text.
 */
test.setTimeout(240_000);

let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

test('regions never previously visited render styled text', async () => {
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

  await window.click('.monaco-editor');
  await window.keyboard.press('Control+End');
  await window.waitForTimeout(3000);

  const rendered = await window.evaluate(() => {
    const lines = Array.from(document.querySelectorAll('.view-line'));
    const withText = lines.filter((line) => (line.textContent ?? '').trim().length > 0);
    // Monaco emits a <span> per token run; a line rendered with no styling at all collapses to one
    // span, so a document where every line has exactly one is an unstyled document.
    const multiSpan = withText.filter((line) => line.querySelectorAll('span').length > 1);
    return { lines: lines.length, withText: withText.length, multiSpan: multiSpan.length };
  });

  expect(rendered.lines).toBeGreaterThan(5);
  expect(rendered.withText).toBeGreaterThan(0);
  expect(rendered.multiSpan).toBeGreaterThan(0);
});
