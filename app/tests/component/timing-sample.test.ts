import { describe, expect, it } from 'vitest';
import { TimingCollector } from '../../src/renderer/timing/collector';

/**
 * A timing sample records what actually happened, and stays silent about what did not.
 *
 * Many interactions never reach the backend — a cursor move, a scroll, a keystroke answered from
 * cached tokens. For those, `roundTripMs` must be *absent*, not zero and not estimated: a zero would
 * be averaged into the round-trip figure and quietly pull it toward zero, making the backend look
 * faster the more often it was not consulted.
 */
describe('TimingCollector', () => {
  it('records input-to-render for an interaction that never reached the backend', () => {
    const collector = new TimingCollector();

    collector.record({ kind: 'keystroke', inputToRenderMs: 8.5 });

    const sample = collector.samples()[0];
    expect(sample?.inputToRenderMs).toBe(8.5);
    expect(sample?.roundTripMs).toBeUndefined();
  });

  it('does not back-fill a round trip with zero', () => {
    const collector = new TimingCollector();
    collector.record({ kind: 'scroll', inputToRenderMs: 4 });

    expect('roundTripMs' in (collector.samples()[0] ?? {})).toBe(false);
  });

  it('records a round trip when the interaction did reach the backend', () => {
    const collector = new TimingCollector();

    collector.record({ kind: 'keystroke', inputToRenderMs: 9, roundTripMs: 22 });

    expect(collector.samples()[0]?.roundTripMs).toBe(22);
  });

  it('reports round-trip percentiles over only the samples that have one', () => {
    const collector = new TimingCollector();
    collector.record({ kind: 'keystroke', inputToRenderMs: 5 });
    collector.record({ kind: 'keystroke', inputToRenderMs: 5, roundTripMs: 20 });
    collector.record({ kind: 'keystroke', inputToRenderMs: 5, roundTripMs: 30 });

    // Including the backend-free interaction would report 16.7 and imply a round trip that never
    // happened, understating what the backend actually costs.
    expect(collector.roundTripP50()).toBe(30);
    expect(collector.inputToRenderP50()).toBe(5);
  });

  it('reports null round-trip percentiles when nothing reached the backend', () => {
    const collector = new TimingCollector();
    collector.record({ kind: 'scroll', inputToRenderMs: 3 });

    expect(collector.roundTripP50()).toBeNull();
    expect(collector.roundTripP95()).toBeNull();
  });

  it('bounds how many samples it retains', () => {
    const collector = new TimingCollector(4);
    for (let i = 0; i < 50; i++) {
      collector.record({ kind: 'keystroke', inputToRenderMs: i });
    }

    expect(collector.samples()).toHaveLength(4);
  });

  it('counts interactions by kind so the panel can say what was measured', () => {
    const collector = new TimingCollector();
    collector.record({ kind: 'keystroke', inputToRenderMs: 1 });
    collector.record({ kind: 'keystroke', inputToRenderMs: 2 });
    collector.record({ kind: 'scroll', inputToRenderMs: 3 });

    expect(collector.countByKind()).toEqual({ keystroke: 2, scroll: 1 });
  });
});
