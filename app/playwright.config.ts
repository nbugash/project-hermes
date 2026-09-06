import { defineConfig } from '@playwright/test';

/**
 * Playwright drives the shell; it does not time it.
 *
 * All latency figures come from in-page performance marks read back via `page.evaluate`, never from
 * driver wall-clock: every Playwright action is an IPC round trip whose overhead is the same order
 * of magnitude as a 16 ms budget. See docs/adr/0001-editor-responsiveness-budgets.md.
 */
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  // Budgets are measured, so retries would mask flakiness rather than reveal it.
  retries: 0,

  // Serial by default because timing measurements must not compete for CPU: four Electron instances
  // rendering at once would inflate every frame figure and the numbers would describe the runner
  // rather than the editor.
  //
  // `npm run e2e:fast` overrides this with --workers=4 and is safe to, because it excludes every
  // @perf test — nothing left in it measures time. Measured on this machine: 7.3 min serial versus
  // 2.1 min parallel, same 16 tests passing. Do NOT add --workers to e2e:perf.
  workers: 1,
  reporter: [['list']],
  use: {
    trace: 'retain-on-failure',
  },
});
