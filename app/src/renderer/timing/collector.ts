import { LatencyWindow } from './LatencyWindow';

export type InteractionKind = 'keystroke' | 'scroll' | 'open';

export interface TimingSample {
  kind: InteractionKind;
  /** Input event to committed frame, always present — every interaction renders something. */
  inputToRenderMs: number;
  /**
   * Request issue to response, present only when this interaction actually reached the backend.
   *
   * Absent rather than zero. Many interactions are answered locally — a cursor move, a scroll, a
   * keystroke served from cached tokens — and a zero for those would be averaged into the round-trip
   * figure, making the backend look faster precisely when it was not consulted at all.
   */
  roundTripMs?: number;
}

/**
 * Collects timing samples for the panel.
 *
 * Holds two windows rather than one: input-to-render covers every interaction, round-trip covers
 * only those that reached the backend. Keeping them separate is what makes the round-trip figure
 * mean "when we asked the backend, this is what it cost".
 */
export class TimingCollector {
  private readonly recent: TimingSample[] = [];
  private readonly inputToRender: LatencyWindow;
  private readonly roundTrip: LatencyWindow;

  constructor(private readonly capacity = 200) {
    this.inputToRender = new LatencyWindow(capacity);
    this.roundTrip = new LatencyWindow(capacity);
  }

  record(sample: TimingSample): void {
    this.recent.push(sample);
    while (this.recent.length > this.capacity) {
      this.recent.shift();
    }

    this.inputToRender.add(sample.inputToRenderMs);
    if (sample.roundTripMs !== undefined) {
      this.roundTrip.add(sample.roundTripMs);
    }
  }

  samples(): readonly TimingSample[] {
    return this.recent;
  }

  inputToRenderP50(): number | null {
    return this.inputToRender.p50();
  }

  inputToRenderP95(): number | null {
    return this.inputToRender.p95();
  }

  roundTripP50(): number | null {
    return this.roundTrip.p50();
  }

  roundTripP95(): number | null {
    return this.roundTrip.p95();
  }

  /** Interaction counts by kind, so the panel can say what the figures are actually over. */
  countByKind(): Record<string, number> {
    const counts: Record<string, number> = {};
    for (const sample of this.recent) {
      counts[sample.kind] = (counts[sample.kind] ?? 0) + 1;
    }
    return counts;
  }
}
