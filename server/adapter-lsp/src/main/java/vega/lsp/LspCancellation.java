package vega.lsp;

import java.util.concurrent.CancellationException;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import vega.core.port.CancellationToken;

/**
 * Bridges lsp4j's {@link CancelChecker} to the core's {@link CancellationToken}.
 *
 * <p>lsp4j signals cancellation by throwing from {@code checkCanceled}; the core signals it by
 * returning true from {@code isCancelled}. Translating here keeps the core free of the protocol
 * library (Constitution Principle III) and, more practically, keeps one cancellation model in the
 * analysis code instead of two.
 */
final class LspCancellation {

    private LspCancellation() {}

    static CancellationToken of(CancelChecker checker) {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                try {
                    checker.checkCanceled();
                    return false;
                } catch (CancellationException cancelled) {
                    return true;
                }
            }

            @Override
            public void cancel() {
                // Cancellation is the client's to declare, via $/cancelRequest. A server-side cancel
                // would be invisible to the client, which would then wait for a reply never coming.
                throw new UnsupportedOperationException("request cancellation is client-driven");
            }
        };
    }
}
