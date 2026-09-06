package vega.core.obs;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Per-request correlation id, carried across thread boundaries.
 *
 * <p>Constitution Principle IX requires the id to appear on every log line emitted while handling a
 * request, on both sides of the protocol boundary, and to survive dispatch from the event loop to
 * the CPU worker pool. A plain {@link ThreadLocal} satisfies the first requirement and quietly fails
 * the second — which is why {@link #propagating} exists and why work must be wrapped before it is
 * submitted.
 */
public final class Correlation {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Correlation() {}

    public static String current() {
        return CURRENT.get();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Binds an id for the duration of the returned scope, restoring any previous id on close. */
    public static Scope beginScope(String correlationId) {
        String previous = CURRENT.get();
        CURRENT.set(correlationId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * Captures the calling thread's correlation id and re-binds it around the work.
     *
     * <p>The id is cleared afterwards rather than left behind: worker threads are pooled and reused,
     * so a leaked id would silently mis-attribute the next request's log lines.
     */
    public static <T> Callable<T> propagating(Callable<T> work) {
        String captured = CURRENT.get();
        return () -> {
            try (Scope ignored = beginScope(captured)) {
                return work.call();
            }
        };
    }

    public static Runnable propagating(Runnable work) {
        String captured = CURRENT.get();
        return () -> {
            try (Scope ignored = beginScope(captured)) {
                work.run();
            }
        };
    }

    public static <T> Supplier<T> propagatingSupplier(Supplier<T> work) {
        String captured = CURRENT.get();
        return () -> {
            try (Scope ignored = beginScope(captured)) {
                return work.get();
            }
        };
    }

    /** Closeable binding; {@code close} does not throw, so it is safe in try-with-resources. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
