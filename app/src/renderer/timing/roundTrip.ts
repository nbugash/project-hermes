/**
 * Attributes backend round-trip time to the request it belongs to.
 *
 * Requests overlap — a keystroke can be issued while the previous one is outstanding, and responses
 * can return out of order — so pairing by arrival order would credit one request with another's
 * duration. Correlation ids are already carried through both runtimes for logging; this reuses them
 * rather than inventing a second identity for the same request.
 */
export class RoundTripTracker {
  private readonly issuedAt = new Map<string, number>();

  constructor(private readonly maxPending = 64) {}

  issued(correlationId: string, atMs: number): void {
    this.issuedAt.set(correlationId, atMs);

    // A response that never arrives would otherwise pin its entry for the life of the session.
    // Dropping the oldest bounds the cost of that at the price of not measuring a very late reply,
    // which is the right trade: a reply that late has already missed every budget.
    while (this.issuedAt.size > this.maxPending) {
      const oldest = this.issuedAt.keys().next();
      if (oldest.done) {
        break;
      }
      this.issuedAt.delete(oldest.value);
    }
  }

  /**
   * @returns the elapsed milliseconds, or null when this id was never issued or already completed —
   *     null rather than a guess, because a fabricated duration is worse than a missing one.
   */
  completed(correlationId: string, atMs: number): number | null {
    const started = this.issuedAt.get(correlationId);
    if (started === undefined) {
      return null;
    }
    this.issuedAt.delete(correlationId);

    // Clamped at zero: a coarse or adjusted clock can place completion before issue, and a negative
    // latency in the panel reads as a bug in the editor rather than in the clock.
    return Math.max(0, atMs - started);
  }

  pending(): number {
    return this.issuedAt.size;
  }
}
