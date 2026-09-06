import { describe, expect, it } from 'vitest';
import { assertProtocolCompatible } from '../../src/protocol-client/version';

/**
 * A version mismatch must fail startup loudly. The alternative — degrading quietly — surfaces later
 * as a malformed response with no obvious cause, which is far more expensive to diagnose.
 */
describe('assertProtocolCompatible', () => {
  it('accepts an exact match', () => {
    expect(() => assertProtocolCompatible('1.0.0', '1.0.0')).not.toThrow();
  });

  it('accepts a server patch or minor ahead within the same major', () => {
    expect(() => assertProtocolCompatible('1.2.3', '1.0.0')).not.toThrow();
  });

  it('rejects a differing major version', () => {
    expect(() => assertProtocolCompatible('2.0.0', '1.0.0')).toThrow(/protocol/i);
  });

  it('rejects a server older than the client within the same major', () => {
    // The client may rely on a method added in 1.1.0; an older server would answer "method not
    // found" at the worst possible moment rather than at startup.
    expect(() => assertProtocolCompatible('1.0.0', '1.1.0')).toThrow(/older/i);
  });

  it('rejects an unparseable version rather than guessing', () => {
    expect(() => assertProtocolCompatible('not-a-version', '1.0.0')).toThrow();
  });
});
