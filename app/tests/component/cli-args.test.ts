import { describe, expect, it } from 'vitest';
import { resolveInitialFile } from '../../src/main/cli';

/**
 * Argument handling decides what opens at startup, so its failure modes are all silent: the wrong
 * file, or an empty window when the user named one. Electron's own argv shape is the trap — it
 * carries the executable and, in development, the script path before any user argument.
 */
describe('resolveInitialFile', () => {
  it('returns the first path argument in a packaged invocation', () => {
    expect(resolveInitialFile(['/usr/bin/vega', '/home/nico/Large.java'], false)).toBe(
      '/home/nico/Large.java',
    );
  });

  it('skips the script path in a development invocation', () => {
    // `electron . file.java` puts the app directory in argv before the user's argument; treating it
    // as the file opens the directory and reports a confusing read error.
    expect(resolveInitialFile(['/usr/bin/electron', '.', '/home/nico/Large.java'], true)).toBe(
      '/home/nico/Large.java',
    );
  });

  it('returns null when no file is named', () => {
    expect(resolveInitialFile(['/usr/bin/vega'], false)).toBeNull();
  });

  it('ignores electron and chromium switches', () => {
    expect(
      resolveInitialFile(['/usr/bin/vega', '--no-sandbox', '--inspect=9229', '/tmp/A.java'], false),
    ).toBe('/tmp/A.java');
  });

  it('returns null when only switches are present', () => {
    expect(resolveInitialFile(['/usr/bin/vega', '--no-sandbox'], false)).toBeNull();
  });
});
