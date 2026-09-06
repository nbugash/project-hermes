package vega.core.port;

/**
 * Lazy traversal over an adapter-owned syntax tree.
 *
 * <p>Constitution Principle III requires this shape explicitly: the core queries syntax through a
 * cursor rather than receiving a translated tree. Materialising a copy per request would cost the
 * whole-file work that Principle VI exists to eliminate — measurement put a full-file query at
 * roughly 320 ms against a 60-line window at 0.31 ms, so the difference is the budget itself.
 *
 * <p>The cursor is a moving pointer, not a snapshot. It is not thread-safe and must not outlive the
 * tree it was opened on.
 */
public interface SyntaxCursor extends AutoCloseable {

    boolean gotoFirstChild();

    boolean gotoNextSibling();

    boolean gotoParent();

    String nodeType();

    int startOffset();

    int endOffset();

    /** Whether the cursor sits on an error or missing node produced by recovery. */
    boolean isErrorNode();

    @Override
    void close();
}
