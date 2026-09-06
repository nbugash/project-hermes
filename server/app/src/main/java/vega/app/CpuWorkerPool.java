package vega.app;

import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import vega.core.obs.Correlation;

/**
 * Dedicated pool for CPU-bound analysis work.
 *
 * <p>Constitution Principle IV forbids parsing, indexing and resolution on a Vert.x event loop, and
 * requires this pool to be separate from the one serving blocking I/O — otherwise a slow file read
 * and a slow reparse contend for the same threads, and the symptom looks like a parser problem.
 *
 * <p>Work is wrapped so the request's correlation id survives the dispatch; a plain submit would
 * lose it exactly where the expensive work happens.
 */
public final class CpuWorkerPool implements AutoCloseable {

    private static final String POOL_NAME = "vega-cpu";

    private final WorkerExecutor executor;

    public CpuWorkerPool(Vertx vertx) {
        this(vertx, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    public CpuWorkerPool(Vertx vertx, int size) {
        // One thread is left for the event loop and I/O so that saturating analysis cannot starve
        // protocol handling — a stalled channel is indistinguishable from a hung backend.
        this.executor = vertx.createSharedWorkerExecutor(POOL_NAME, size);
    }

    public <T> CompletableFuture<T> submit(Callable<T> work) {
        Callable<T> propagating = Correlation.propagating(work);
        CompletableFuture<T> result = new CompletableFuture<>();

        executor
                .executeBlocking(propagating::call, false)
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally);

        return result;
    }

    @Override
    public void close() {
        executor.close();
    }
}
