import { describe, expect, it } from 'vitest';
import { describeSaveOutcome, hashText } from '../../src/renderer/editor/save';

/**
 * What the user is told when a save does not happen.
 *
 * Every failure here leaves unsaved work in the buffer, so the message has to say what went wrong
 * and imply what to do about it. "Save failed" tells the user nothing and invites them to try again
 * into the same wall.
 */
describe('describeSaveOutcome', () => {
  it('reports a successful write without alarming the user', () => {
    const outcome = describeSaveOutcome({ status: 'written', contentHash: 'abc' });

    expect(outcome.failed).toBe(false);
    expect(outcome.message).toMatch(/saved/i);
  });

  it('explains a disk change as someone else having edited the file', () => {
    const outcome = describeSaveOutcome({ status: 'disk-changed' });

    expect(outcome.failed).toBe(true);
    // The distinction matters: this is not a bug, and the user's next step is to reconcile.
    expect(outcome.message).toMatch(/changed on disk/i);
  });

  it('explains a mirror mismatch without blaming the user', () => {
    const outcome = describeSaveOutcome({ status: 'mirror-mismatch' });

    expect(outcome.failed).toBe(true);
    expect(outcome.message).toMatch(/out of sync|synchron/i);
  });

  it('surfaces the backend reason for a plain failure', () => {
    const outcome = describeSaveOutcome({ status: 'failed', reason: 'Permission denied' });

    expect(outcome.failed).toBe(true);
    expect(outcome.message).toContain('Permission denied');
  });

  it('still says something useful when the backend supplies no reason', () => {
    expect(describeSaveOutcome({ status: 'failed' }).message).toMatch(/could not be (written|saved)/i);
  });

  it('treats an unrecognised status as a failure rather than a success', () => {
    // A newer backend could return a status this client does not know. Assuming success would tell
    // the user their work is on disk when it may not be.
    const outcome = describeSaveOutcome({ status: 'something-new' });

    expect(outcome.failed).toBe(true);
  });
});

describe('hashText', () => {
  it('produces a stable hex digest', async () => {
    const first = await hashText('class A {}\n');
    const second = await hashText('class A {}\n');

    expect(first).toBe(second);
    expect(first).toMatch(/^[0-9a-f]{64}$/);
  });

  it('hashes UTF-8 bytes so the backend computes the same value', async () => {
    // The literal SHA-256 of the UTF-8 bytes of "café", computed independently of this code. Hashing
    // the UTF-16 the runtime holds internally would produce 8c9f3eed8d0b4c75... instead, and the backend
    // would refuse every save of a file containing a non-ASCII character.
    expect(await hashText('café')).toBe('850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e');
    expect(await hashText('café')).not.toBe('8c9f3eed8d0b4c75bdde53bf22d847cb5a1b1318e9d5ce0186142c5602ca9baa');
  });
});
