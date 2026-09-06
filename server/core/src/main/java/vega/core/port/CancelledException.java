package vega.core.port;

/** Thrown at a cancellation checkpoint when the work has been superseded. */
public class CancelledException extends RuntimeException {

    public CancelledException() {
        super("Work was cancelled");
    }
}
