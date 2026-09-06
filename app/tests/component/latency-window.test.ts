import { describe, expect, it } from 'vitest';
import { LatencyWindow } from '../../src/renderer/timing/LatencyWindow';

/**
 * The panel reports rolling p50/p95 over recent interactions.
 *
 * Bounded on purpose: an editing session is unbounded, and a window that grew with it would both
 * leak and report figures dominated by what happened minutes ago rather than now. The budgets are
 * about how the editor feels at this moment.
 */
describe('LatencyWindow', () => {
  it('reports nothing before any sample arrives', () => {
    const window = new LatencyWindow(10);

    expect(window.size()).toBe(0);
    expect(window.p50()).toBeNull();
    expect(window.p95()).toBeNull();
  });

  it('reports the single sample as both percentiles', () => {
    const window = new LatencyWindow(10);
    window.add(7);

    expect(window.p50()).toBe(7);
    expect(window.p95()).toBe(7);
  });

  it('computes percentiles over the samples it holds', () => {
    const window = new LatencyWindow(100);
    for (let i = 1; i <= 100; i++) {
      window.add(i);
    }

    expect(window.p50()).toBe(51);
    expect(window.p95()).toBe(96);
  });

  it('is unaffected by the order samples arrive in', () => {
    const ascending = new LatencyWindow(10);
    const descending = new LatencyWindow(10);
    for (let i = 1; i <= 10; i++) {
      ascending.add(i);
      descending.add(11 - i);
    }

    expect(ascending.p50()).toBe(descending.p50());
    expect(ascending.p95()).toBe(descending.p95());
  });

  it('never exceeds its capacity', () => {
    const window = new LatencyWindow(5);
    for (let i = 0; i < 1000; i++) {
      window.add(i);
    }

    expect(window.size()).toBe(5);
  });

  it('drops the oldest sample when full', () => {
    const window = new LatencyWindow(3);
    window.add(100);
    window.add(1);
    window.add(2);
    window.add(3);

    // The 100 is gone, so it cannot drag the percentiles; a window that kept it would keep
    // reporting a spike long after the editor recovered from it.
    expect(window.p95()).toBe(3);
  });

  it('reflects only recent samples after a burst of old ones', () => {
    const window = new LatencyWindow(4);
    for (let i = 0; i < 20; i++) {
      window.add(50);
    }
    for (let i = 0; i < 4; i++) {
      window.add(2);
    }

    expect(window.p50()).toBe(2);
  });

  it('rejects a non-positive capacity rather than silently holding nothing', () => {
    expect(() => new LatencyWindow(0)).toThrow(/capacity/i);
  });
})
