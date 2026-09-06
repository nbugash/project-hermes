import { describe, expect, it } from 'vitest';
import { measureToNextFrame } from '../../src/renderer/timing/probe';

/**
 * The probe measures from an input event to the frame that was actually committed.
 *
 * Event Timing cannot be used as the gate: its `duration` is specified as quantized to 8 ms
 * granularity, and `durationThreshold` is clamped to a 16 ms minimum, so a 16 ms budget is
 * unobservable through it. Hence this hand-rolled probe, with the frame callback injected so the
 * arithmetic is testable without a real compositor.
 */
describe('measureToNextFrame', () => {
  it('reports the elapsed time between the input timestamp and the committed frame', async () => {
    const clock = { now: 100 };
    const afterFrame = (cb: () => void) => {
      clock.now = 112;
      cb();
    };

    const elapsed = await measureToNextFrame(100, () => clock.now, afterFrame);

    expect(elapsed).toBe(12);
  });

  it('measures from the input timestamp, not from when the probe was armed', async () => {
    // The input event happened at t=50; the probe is armed at t=60 inside the handler. Measuring
    // from arming would under-report by the whole input-delay portion, which is exactly the part
    // a slow handler makes worse.
    const clock = { now: 60 };
    const afterFrame = (cb: () => void) => {
      clock.now = 70;
      cb();
    };

    const elapsed = await measureToNextFrame(50, () => clock.now, afterFrame);

    expect(elapsed).toBe(20);
  });

  it('never reports a negative duration when clocks disagree', async () => {
    const afterFrame = (cb: () => void) => cb();

    const elapsed = await measureToNextFrame(500, () => 490, afterFrame);

    expect(elapsed).toBe(0);
  });
});
