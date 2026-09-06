package vega.core.highlight;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures whether edit-proximity narrowing ever produces the wrong answer.
 *
 * <p>Narrowing assumes a user cannot type outside the viewport, so bounding the repaint to a window
 * around the edit is safe (research D13). The assumption is sound for typing and not obviously sound
 * for everything else — a paste, a multi-cursor edit, or a change whose effect genuinely reaches
 * beyond the window would each break it.
 *
 * <p>Research asked for the disagreement rate to be <em>measured</em> before a reconciliation pass is
 * built, rather than building one speculatively against a problem that may not exist. This class is
 * that measurement: it compares what narrowing produced against what a full re-query would have
 * produced, and counts the disagreements.
 *
 * <p>Counters only. It deliberately does not repair a disagreement — doing so would be the
 * speculative reconciliation pass, built before the evidence justifying it exists.
 */
public final class NarrowingAudit {

    private final AtomicLong comparisons = new AtomicLong();
    private final AtomicLong disagreements = new AtomicLong();
    private final AtomicLong tokensMissed = new AtomicLong();

    /**
     * Compares a narrowed result against the tokens a full re-query produced.
     *
     * @param narrowed tokens produced by the narrowed re-highlight
     * @param full tokens a whole-document query produced for the same version
     * @param window the region the narrowed pass claimed to cover
     * @return true when the two agree within the window
     */
    public boolean record(List<Token> narrowed, List<Token> full, ChangedRange window) {
        comparisons.incrementAndGet();

        List<Token> fullInsideWindow =
                full.stream()
                        .filter(token -> token.start() >= window.start() && token.end() <= window.end())
                        .toList();
        List<Token> narrowedInsideWindow =
                narrowed.stream()
                        .filter(token -> token.start() >= window.start() && token.end() <= window.end())
                        .toList();

        if (narrowedInsideWindow.equals(fullInsideWindow)) {
            return true;
        }

        disagreements.incrementAndGet();
        tokensMissed.addAndGet(Math.abs(fullInsideWindow.size() - narrowedInsideWindow.size()));
        return false;
    }

    public long comparisons() {
        return comparisons.get();
    }

    public long disagreements() {
        return disagreements.get();
    }

    public long tokensMissed() {
        return tokensMissed.get();
    }

    /** Disagreements as a fraction of comparisons, or 0 when nothing has been compared yet. */
    public double disagreementRate() {
        long total = comparisons.get();
        return total == 0 ? 0 : (double) disagreements.get() / total;
    }

    /** One line for the timing panel and the spike's findings. */
    public String summary() {
        return String.format(
                "narrowing audit: %d comparisons, %d disagreements (%.4f), %d tokens missed",
                comparisons(), disagreements(), disagreementRate(), tokensMissed());
    }
}
