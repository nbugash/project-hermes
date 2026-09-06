package vega.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

/**
 * The initial parse of a large file is the one operation slow enough for a user to notice without
 * feedback. FR-011 wants it reported rather than presented as a frozen window, and the failure mode
 * that matters is a begin without an end — a progress indicator that never goes away is worse than
 * none at all, so that pairing is what these tests pin down.
 */
class ProgressReportingTest {

    @Test
    void reportsBeginAndEndAroundTheWork() {
        CapturingClient client = new CapturingClient();
        ProgressReporter reporter = new ProgressReporter(() -> client);

        String result = reporter.around("Parsing Sample.java", () -> "parsed");

        assertEquals("parsed", result);
        assertEquals(2, client.notifications.size(), "expected exactly a begin and an end");
        assertTrue(client.notifications.get(0).getValue().getLeft() instanceof WorkDoneProgressBegin);
        assertTrue(client.notifications.get(1).getValue().getLeft() instanceof WorkDoneProgressEnd);
    }

    @Test
    void endsProgressEvenWhenTheWorkFails() {
        CapturingClient client = new CapturingClient();
        ProgressReporter reporter = new ProgressReporter(() -> client);

        try {
            reporter.around(
                    "Parsing Sample.java",
                    () -> {
                        throw new IllegalStateException("parse blew up");
                    });
        } catch (IllegalStateException expected) {
            // The exception must still propagate; swallowing it would hide the real failure.
        }

        assertEquals(2, client.notifications.size(), "an aborted parse must still end its progress");
        assertTrue(client.notifications.get(1).getValue().getLeft() instanceof WorkDoneProgressEnd);
    }

    @Test
    void usesADistinctTokenPerOperation() {
        CapturingClient client = new CapturingClient();
        ProgressReporter reporter = new ProgressReporter(() -> client);

        reporter.around("first", () -> null);
        reporter.around("second", () -> null);

        String firstToken = client.notifications.get(0).getToken().getLeft();
        String secondToken = client.notifications.get(2).getToken().getLeft();
        assertFalse(
                firstToken.equals(secondToken),
                "reusing a progress token makes concurrent operations cancel each other's indicator");
    }

    @Test
    void doesNotFailWhenNoClientIsConnected() {
        ProgressReporter reporter = new ProgressReporter(() -> null);

        // The server runs headless in tests and briefly before the client attaches; progress is
        // reporting, not a dependency, so its absence must never break the work itself.
        assertEquals("done", reporter.around("no client", () -> "done"));
    }

    private static final class CapturingClient implements LanguageClient {
        final List<ProgressParams> notifications = new ArrayList<>();

        @Override
        public void notifyProgress(ProgressParams params) {
            notifications.add(params);
        }

        @Override
        public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void telemetryEvent(Object object) {}

        @Override
        public void publishDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams diagnostics) {}

        @Override
        public void showMessage(org.eclipse.lsp4j.MessageParams messageParams) {}

        @Override
        public CompletableFuture<org.eclipse.lsp4j.MessageActionItem> showMessageRequest(
                org.eclipse.lsp4j.ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(org.eclipse.lsp4j.MessageParams message) {}
    }
}
