package vega.core.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Constitution Principle IX requires the correlation id to survive dispatch from the event loop to
 * the worker pool. Thread-bound context that is lost at a dispatch boundary does not satisfy it —
 * and that boundary is exactly where the expensive work happens, so losing it there means losing it
 * everywhere that matters.
 */
class CorrelationTest {

    @Test
    void hasNoCorrelationByDefault() {
        assertNull(Correlation.current());
    }

    @Test
    void exposesTheCorrelationIdWithinItsScope() {
        try (var scope = Correlation.beginScope("req-42")) {
            assertEquals("req-42", Correlation.current());
        }
        assertNull(Correlation.current());
    }

    @Test
    void restoresThePreviousCorrelationWhenScopesNest() {
        try (var outer = Correlation.beginScope("outer")) {
            try (var inner = Correlation.beginScope("inner")) {
                assertEquals("inner", Correlation.current());
            }
            assertEquals("outer", Correlation.current());
        }
    }

    @Test
    void propagatesAcrossDispatchToAnotherThread() throws Exception {
        ExecutorService workerPool = Executors.newSingleThreadExecutor();
        try {
            String observed;
            try (var scope = Correlation.beginScope("req-99")) {
                // This is the case that matters: the handler captures the work on the event loop
                // and hands it to a CPU worker. A plain submit() would lose the id here.
                Future<String> result = workerPool.submit(Correlation.propagating(Correlation::current));
                observed = result.get();
            }
            assertEquals("req-99", observed);
        } finally {
            workerPool.shutdownNow();
        }
    }

    @Test
    void doesNotLeakTheCorrelationIntoUnrelatedWork() throws Exception {
        ExecutorService workerPool = Executors.newSingleThreadExecutor();
        try {
            try (var scope = Correlation.beginScope("req-1")) {
                workerPool.submit(Correlation.propagating(Correlation::current)).get();
            }
            String afterScope = workerPool.submit(Correlation::current).get();
            assertNull(afterScope, "a worker thread must not retain an id from earlier work");
        } finally {
            workerPool.shutdownNow();
        }
    }

    @Test
    void generatesDistinctIds() {
        assertTrue(!Correlation.newId().equals(Correlation.newId()));
    }
}
