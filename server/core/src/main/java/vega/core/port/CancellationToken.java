package vega.core.port;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation signal.
 *
 * <p>Java cannot safely stop a thread from outside, so "cancellable" means the work opts out at
 * defined checkpoints rather than the caller killing it. Constitution Principle IV requires that
 * cancellation actually stop in-flight work: discarding the result while a worker keeps running is
 * a defect, because the pool starves precisely when typing is fastest.
 */
public interface CancellationToken {

    boolean isCancelled();

    /** Cancels the work. Idempotent, and safe to call from another thread. */
    void cancel();

    /** Checkpoint. Long-running analysis calls this at intervals proportional to its work. */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }

    static CancellationToken cancellable() {
        return new AtomicCancellationToken();
    }

    /** For work that genuinely cannot be abandoned, such as applying an already-accepted edit. */
    static CancellationToken never() {
        return NeverCancelled.INSTANCE;
    }

    final class AtomicCancellationToken implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    final class NeverCancelled implements CancellationToken {
        private static final NeverCancelled INSTANCE = new NeverCancelled();

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void cancel() {
            // Intentionally inert: this token exists to express "not abandonable".
        }
    }
}
