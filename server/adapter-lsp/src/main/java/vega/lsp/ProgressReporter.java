package vega.lsp;

import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Reports long operations to the editor through {@code $/progress}.
 *
 * <p>The initial parse of a 50,000-line file is the one operation slow enough to look like a hang.
 * FR-011 asks for it to be visible; the important half of that is the end notification, since an
 * indicator that never clears reads as a worse failure than no indicator at all. Both the success
 * and failure paths therefore end the same progress token.
 *
 * <p>Progress is reporting, never a dependency: if no client is attached, the work still runs.
 */
public final class ProgressReporter {

    private final Supplier<LanguageClient> client;

    public ProgressReporter(Supplier<LanguageClient> client) {
        this.client = client;
    }

    public <T> T around(String title, Supplier<T> work) {
        LanguageClient target = client.get();
        if (target == null) {
            return work.get();
        }

        // A fresh token per operation: reusing one would let a finishing operation clear the
        // indicator belonging to another that is still running.
        String token = UUID.randomUUID().toString();
        target.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(token)));

        WorkDoneProgressBegin begin = new WorkDoneProgressBegin();
        begin.setTitle(title);
        begin.setCancellable(false);
        target.notifyProgress(new ProgressParams(Either.forLeft(token), Either.forLeft(begin)));

        try {
            return work.get();
        } finally {
            target.notifyProgress(
                    new ProgressParams(Either.forLeft(token), Either.forLeft(new WorkDoneProgressEnd())));
        }
    }
}
