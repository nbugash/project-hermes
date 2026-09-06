package vega.core.port;

import java.util.List;
import vega.core.highlight.ChangedRange;

/**
 * An adapter-owned parse result. The core holds it opaquely and inspects it only through a cursor.
 *
 * <p>Closing releases native memory held by the adapter, so trees are scoped, not cached
 * indefinitely.
 */
public interface ParsedTree extends AutoCloseable {

    boolean hasError();

    /**
     * Structural differences against an earlier tree.
     *
     * <p>Conservative by contract: the returned ranges may be far larger than the region whose
     * appearance changed, and on a brace edit they routinely cover the whole document. Callers must
     * narrow before repainting.
     */
    List<ChangedRange> changedRangesSince(ParsedTree previous);

    SyntaxCursor cursor();

    @Override
    void close();
}
