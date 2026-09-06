import { describe, expect, it } from 'vitest';
import { ResultGate } from '../../src/renderer/editor/semantic-tokens';

/**
 * Results are keyed to the document version they were computed from. A result that arrives after the
 * user has typed again describes text that no longer exists, and painting it puts colour at offsets
 * that have since moved — visibly wrong, and not corrected until the next result lands.
 */
describe('ResultGate', () => {
  it('accepts a result for the current version', () => {
    const gate = new ResultGate();
    gate.observeVersion(5);

    expect(gate.accepts(5)).toBe(true);
  });

  it('discards a result for a superseded version', () => {
    const gate = new ResultGate();
    gate.observeVersion(5);
    gate.observeVersion(6);

    expect(gate.accepts(5)).toBe(false);
  });

  it('discards a result from the future, which cannot be trusted either', () => {
    const gate = new ResultGate();
    gate.observeVersion(5);

    // The client has not applied version 7 yet, so painting it would show tokens for edits the
    // buffer does not contain.
    expect(gate.accepts(7)).toBe(false);
  });

  it('accepts results again once the version catches up', () => {
    const gate = new ResultGate();
    gate.observeVersion(5);
    expect(gate.accepts(4)).toBe(false);

    gate.observeVersion(6);
    expect(gate.accepts(6)).toBe(true);
  });

  it('rejects everything before any version has been observed', () => {
    expect(new ResultGate().accepts(1)).toBe(false);
  });
});
