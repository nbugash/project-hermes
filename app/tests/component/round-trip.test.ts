import { describe, expect, it } from 'vitest';
import { RoundTripTracker } from '../../src/renderer/timing/roundTrip';

/**
 * Round-trip time is attributed by correlation id, not by arrival order.
 *
 * Requests overlap: a keystroke can be issued while the previous one is still outstanding, and
 * responses can return out of order. Pairing by order would attribute one request's duration to
 * another and produce figures that look plausible and are wrong.
 */
describe('RoundTripTracker', () => {
  it('measures the time between issue and completion of the same id', () => {
    const tracker = new RoundTripTracker();
    tracker.issued('a', 100);

    expect(tracker.completed('a', 130)).toBe(30);
  });

  it('attributes overlapping requests to the right ids', () => {
    const tracker = new RoundTripTracker();
    tracker.issued('a', 100);
    tracker.issued('b', 110);

    // 'b' returns first. Pairing by arrival order would credit 'a' with b's 15 ms.
    expect(tracker.completed('b', 125)).toBe(15);
    expect(tracker.completed('a', 160)).toBe(60);
  });

  it('returns null for a completion with no matching request', () => {
    const tracker = new RoundTripTracker();

    // A response to a request issued before a reload, or one this tracker never saw. Inventing a
    // duration here would be fabricating a measurement.
    expect(tracker.completed('never-issued', 100)).toBeNull();
  });

  it('returns null when the same id completes twice', () => {
    const tracker = new RoundTripTracker();
    tracker.issued('a', 100);
    tracker.completed('a', 120);

    expect(tracker.completed('a', 140)).toBeNull();
  });

  it('reports how many requests are still outstanding', () => {
    const tracker = new RoundTripTracker();
    tracker.issued('a', 100);
    tracker.issued('b', 100);
    tracker.completed('a', 110);

    expect(tracker.pending()).toBe(1);
  });

  it('bounds the outstanding set so lost responses cannot leak', () => {
    const tracker = new RoundTripTracker(3);
    for (let i = 0; i < 10; i++) {
      tracker.issued(`id-${i}`, i);
    }

    // Requests whose responses never arrive would otherwise accumulate for the life of the session.
    expect(tracker.pending()).toBe(3);
    expect(tracker.completed('id-0', 100)).toBeNull();
    expect(tracker.completed('id-9', 100)).toBe(91);
  });

  it('never reports a negative duration', () => {
    const tracker = new RoundTripTracker();
    tracker.issued('a', 200);

    // Clock adjustments and coarse timer resolution can make completion appear earlier than issue.
    expect(tracker.completed('a', 150)).toBe(0);
  });
});
