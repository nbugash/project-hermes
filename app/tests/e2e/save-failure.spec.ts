import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));
const repoRoot = path.dirname(appRoot);
const backend = path.join(repoRoot, 'server/app/build/install/app/bin/app');

/**
 * A failed save must keep the user's work and say why it failed.
 *
 * This is the highest-stakes failure in the product: everything else costs the user a repaint, and
 * this one can cost them their edits. The buffer must survive, and the message has to distinguish
 * "someone else changed the file" from "the editor is broken", because those call for different
 * responses.
 */
test.setTimeout(180_000);

let app: ElectronApplication;

test.afterEach(async () => {
  await app?.close();
});

test('a save refused because the file changed on disk keeps the buffer and explains itself', async () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'vega-save-'));
  const file = path.join(dir, 'Sample.java');
  writeFileSync(file, 'class Sample {\n    int x = 1;\n}\n');

  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox', file],
    env: { ...process.env, VEGA_BACKEND_COMMAND: backend, VEGA_BACKEND_ARGS: '' },
  });

  const window = await app.firstWindow();
  await window.waitForFunction(
    () => document.querySelectorAll('.view-line').length > 1,
    undefined,
    { timeout: 90_000 },
  );

  await window.click('.monaco-editor');
  await window.keyboard.press('Control+End');
  await window.keyboard.type('// edited');

  // Someone else changes the file while it is open — a rebase, a formatter, another editor.
  writeFileSync(file, 'class Sample {\n    int changedByOtherTool = 99;\n}\n');

  await window.click('[data-testid="save"]');
  await expect(window.locator('[data-testid="save-status"]')).toContainText(/changed on disk/i);

  // The other tool's change survives: refusing has to mean refusing, not refusing after writing.
  expect(readFileSync(file, 'utf8')).toContain('changedByOtherTool');

  // And the user's edit is still in the buffer, not silently discarded.
  const buffer = await window.evaluate(
    // Monaco renders spaces as non-breaking spaces, so the rendered text does not match a plain
    // ASCII space. Normalising here keeps the assertion about content rather than about rendering.
    () => (document.querySelector('.monaco-editor')?.textContent ?? '').replace(/\u00a0/g, ' '),
  );
  expect(buffer).toContain('// edited');
});

test('a successful save writes the buffer and reports it', async () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'vega-save-ok-'));
  const file = path.join(dir, 'Ok.java');
  writeFileSync(file, 'class Ok {\n}\n');

  app = await electron.launch({
    args: [path.join(appRoot, 'dist/main/main.js'), '--no-sandbox', file],
    env: { ...process.env, VEGA_BACKEND_COMMAND: backend, VEGA_BACKEND_ARGS: '' },
  });

  const window = await app.firstWindow();
  await window.waitForFunction(
    () => document.querySelectorAll('.view-line').length > 1,
    undefined,
    { timeout: 90_000 },
  );

  await window.click('[data-testid="save"]');
  await expect(window.locator('[data-testid="save-status"]')).toContainText(/saved/i);

  // Unmodified: the bytes on disk must be exactly what they were.
  expect(readFileSync(file, 'utf8')).toBe('class Ok {\n}\n');
});
