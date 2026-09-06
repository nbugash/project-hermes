/**
 * Holds the document's semantic tokens and the baselines deltas are expressed against.
 *
 * Two things are kept apart deliberately. `current` is what should be on screen and is never
 * discarded for memory reasons — dropping it would un-colour text the user is looking at. Baselines
 * exist only so a delta can be applied, are worth a full token array each, and are therefore
 * bounded: on a 50,000-line file, retaining one per keystroke costs megabytes a minute.
 */
export class TokenStore {
  private tokens: Uint32Array | null = null;
  private full = false;
  private readonly baselines = new Map<string, Uint32Array>();

  constructor(private readonly maxBaselines = 8) {}

  current(): Uint32Array | null {
    return this.tokens;
  }

  /** Whether tokens covering the whole document have arrived, not just the initial viewport. */
  hasFullDocument(): boolean {
    return this.full;
  }

  accept(resultId: string, data: Uint32Array): void {
    this.tokens = data;
    this.baselines.set(resultId, data);
    this.evictOldest();
  }

  /**
   * Accepts a result known to cover the whole document.
   *
   * Tracked separately from a viewport result because it is what makes scrolling into a
   * never-visited region safe: until it arrives, some of the file has no tokens at all.
   */
  acceptFullDocument(resultId: string, data: Uint32Array): void {
    this.accept(resultId, data);
    this.full = true;
  }

  baselineFor(resultId: string): Uint32Array | null {
    return this.baselines.get(resultId) ?? null;
  }

  release(resultId: string): void {
    this.baselines.delete(resultId);
  }

  private evictOldest(): void {
    while (this.baselines.size > this.maxBaselines) {
      // Map iterates in insertion order, so the first key is the oldest baseline.
      const oldest = this.baselines.keys().next();
      if (oldest.done) {
        return;
      }
      this.baselines.delete(oldest.value);
    }
  }
}
