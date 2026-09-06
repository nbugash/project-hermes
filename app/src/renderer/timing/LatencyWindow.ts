/**
 * A bounded rolling window of latency samples, reporting p50 and p95.
 *
 * Bounded deliberately. An editing session is unbounded, so an unbounded window would both grow
 * without limit and report figures dominated by what happened minutes ago. The budgets describe how
 * the editor feels right now, so the window has to forget.
 *
 * Percentiles are computed by sorting on read rather than maintaining order on write: writes happen
 * once per interaction and reads only when the panel repaints, so the cheap path is the one that
 * runs on the input path.
 */
export class LatencyWindow {
  private readonly samples: number[] = [];
  private next = 0;

  constructor(private readonly capacity: number) {
    if (capacity <= 0) {
      throw new Error(`LatencyWindow capacity must be positive, was ${capacity}`);
    }
  }

  add(sample: number): void {
    if (this.samples.length < this.capacity) {
      this.samples.push(sample);
      return;
    }
    // Ring buffer: overwrite the oldest rather than shifting, which would be O(n) per keystroke.
    this.samples[this.next] = sample;
    this.next = (this.next + 1) % this.capacity;
  }

  size(): number {
    return this.samples.length;
  }

  p50(): number | null {
    return this.percentile(0.5);
  }

  p95(): number | null {
    return this.percentile(0.95);
  }

  private percentile(fraction: number): number | null {
    if (this.samples.length === 0) {
      return null;
    }
    const sorted = [...this.samples].sort((a, b) => a - b);
    const index = Math.min(sorted.length - 1, Math.floor(sorted.length * fraction));
    return sorted[index] ?? null;
  }
}
