package vega.core.highlight;

import java.util.List;
import java.util.UUID;
import vega.core.document.Document;
import vega.core.document.Edit;
import vega.core.port.CancellationToken;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;
import vega.core.port.SyntaxParserPort;

/**
 * Re-analyses a document after an edit, cooperatively and within a bounded region.
 *
 * <p>Cancellation is checked at each stage boundary — before parsing, after parsing, and throughout
 * the tree walk — because Constitution Principle IV requires abandoned work to actually stop. A
 * worker that keeps parsing a version the user has already typed past starves the pool exactly when
 * typing is fastest, so discarding the result at the end is not enough.
 */
public final class HighlightService {

    private final SyntaxParserPort parser;
    private final Highlighter highlighter = new Highlighter();

    public HighlightService(SyntaxParserPort parser) {
        this.parser = parser;
    }

    /**
     * The tree and styling produced by one edit.
     *
     * @param tree the new parse tree, owned by the caller and closed by it
     * @param window the region actually re-highlighted, for the caller to repaint
     */
    public record Reanalysis(ParsedTree tree, HighlightResult result, ChangedRange window) {}

    public Reanalysis reanalyse(
            ParsedTree previousTree,
            Document document,
            Edit edit,
            CharSequence newText,
            CancellationToken cancellation) {

        // Checked before the parser is touched: a request already superseded when it reaches the
        // worker should cost nothing at all.
        cancellation.throwIfCancelled();

        ParsedTree tree = parser.reparse(previousTree, edit, newText, cancellation);

        // Checked again on the way out: the parse is the long step, and the user may well have typed
        // during it.
        cancellation.throwIfCancelled();

        // Deliberately after the reparse. Comparing changed ranges requires the *edited* old tree —
        // the one whose positions the reparse shifted — which is what tree-sitter's own API expects.
        // The ordering looks like a use-after-consume and is the opposite: doing it earlier would
        // compare against a tree that predates the edit and report the wrong ranges.
        ChangedRange window =
                HighlightNarrowing.narrow(
                        tree.changedRangesSince(previousTree), edit.start(), newText.length());

        List<Token> tokens;
        try (SyntaxCursor cursor = tree.cursor()) {
            tokens = highlighter.highlight(cursor, window, cancellation);
        }

        cancellation.throwIfCancelled();
        return new Reanalysis(
                tree,
                HighlightResult.full(document.version(), UUID.randomUUID().toString(), tokens),
                window);
    }
}
