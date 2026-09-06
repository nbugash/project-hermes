package vega.syntax;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters the budget measurements read.
 *
 * <p>Package-private and separate from the adapter on purpose. These earned their place — the
 * byte-read counter is what proved incremental reparsing was actually incremental, and the timing
 * split is what attributed the cost between splicing and parsing — but public mutable statics on a
 * production class are test-only code living in the wrong place, where any caller can reach them and
 * nothing says what they are for.
 *
 * <p>They are deliberately not removed. Re-deriving these numbers cost a long debugging session
 * once; keeping the instrument is cheaper than rebuilding it the next time a budget moves.
 */
final class ParseDiagnostics {

    /** Bytes tree-sitter has requested through the read callback. */
    static final AtomicLong BYTES_READ = new AtomicLong();

    /** Time spent splicing the edit into the retained source bytes. */
    static final AtomicLong SPLICE_NANOS = new AtomicLong();

    /** Time spent converting offsets to rows and columns. */
    static final AtomicLong POINT_NANOS = new AtomicLong();

    /** Time spent inside tree-sitter's own incremental parse. */
    static final AtomicLong PARSE_NANOS = new AtomicLong();

    private ParseDiagnostics() {}

    /** Snapshot for a measurement window, so callers subtract rather than reset shared state. */
    record Snapshot(long bytesRead, long spliceNanos, long pointNanos, long parseNanos) {
        static Snapshot take() {
            return new Snapshot(
                    BYTES_READ.get(), SPLICE_NANOS.get(), POINT_NANOS.get(), PARSE_NANOS.get());
        }

        Snapshot since(Snapshot earlier) {
            return new Snapshot(
                    bytesRead - earlier.bytesRead,
                    spliceNanos - earlier.spliceNanos,
                    pointNanos - earlier.pointNanos,
                    parseNanos - earlier.parseNanos);
        }
    }
}
