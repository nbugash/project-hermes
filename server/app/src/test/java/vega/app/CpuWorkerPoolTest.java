package vega.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vega.core.obs.Correlation;

/**
 * Constitution Principle IV: parsing, indexing and resolution must never run on a Vert.x event
 * loop. Blocking the event loop is a defect, not a tuning parameter.
 */
class CpuWorkerPoolTest {

    private Vertx vertx;
    private CpuWorkerPool pool;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        pool = new CpuWorkerPool(vertx);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        vertx.close();
    }

    @Test
    void runsCpuWorkOffTheEventLoop() throws Exception {
        String threadName = pool.submit(() -> Thread.currentThread().getName()).get(10, TimeUnit.SECONDS);

        assertFalse(
                threadName.contains("eventloop"),
                "CPU-bound work ran on an event loop thread: " + threadName);
        assertTrue(threadName.contains("vega-cpu"), "expected the dedicated pool, got " + threadName);
    }

    @Test
    void carriesTheCorrelationIdOntoTheWorkerThread() throws Exception {
        String observed;
        try (var scope = Correlation.beginScope("req-7")) {
            observed = pool.submit(Correlation::current).get(10, TimeUnit.SECONDS);
        }

        assertEquals("req-7", observed);
    }

    @Test
    void reportsFailuresRatherThanSwallowingThem() {
        var future = pool.submit(() -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(
                org.junit.jupiter.api.Assertions.assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> future.get(10, TimeUnit.SECONDS))
                        .getCause()
                        .getMessage()
                        .contains("boom"));
    }
}
