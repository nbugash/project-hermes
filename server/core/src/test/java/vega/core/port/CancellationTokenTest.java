package vega.core.port;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Cancellation is cooperative because Java cannot safely stop a thread from outside. The point is
 * reclaiming the worker thread: in an IDE nearly every highlight request is superseded by the next
 * keystroke, so abandoning work quickly is a throughput property, not a tidiness one.
 */
class CancellationTokenTest {

    @Test
    void anUncancelledTokenPassesItsCheckpoints() {
        CancellationToken token = CancellationToken.cancellable();

        assertFalse(token.isCancelled());
        assertDoesNotThrow(token::throwIfCancelled);
    }

    @Test
    void aCancelledTokenFailsAtItsNextCheckpoint() {
        CancellationToken token = CancellationToken.cancellable();

        token.cancel();

        assertTrue(token.isCancelled());
        assertThrows(CancelledException.class, token::throwIfCancelled);
    }

    @Test
    void cancellationIsIdempotent() {
        CancellationToken token = CancellationToken.cancellable();

        token.cancel();
        token.cancel();

        assertTrue(token.isCancelled());
    }

    @Test
    void theNeverCancelledTokenIsUsableForWorkThatCannotBeAbandoned() {
        assertDoesNotThrow(CancellationToken.never()::throwIfCancelled);
        assertFalse(CancellationToken.never().isCancelled());
    }
}
