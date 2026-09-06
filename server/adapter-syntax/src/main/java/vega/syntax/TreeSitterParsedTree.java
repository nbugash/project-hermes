package vega.syntax;

import io.github.treesitter.jtreesitter.Range;
import io.github.treesitter.jtreesitter.Tree;
import java.util.ArrayList;
import java.util.List;
import vega.core.document.LineIndex;
import vega.core.highlight.ChangedRange;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;

/** Adapter-owned parse result. The core sees only the {@link ParsedTree} port. */
final class TreeSitterParsedTree implements ParsedTree {

    private final Tree tree;
    private final SourceBuffer source;
    private final LineIndex lineIndex;
    private final Utf8Utf16Offsets offsets;

    TreeSitterParsedTree(Tree tree, SourceBuffer source, LineIndex lineIndex) {
        this.tree = tree;
        this.source = source;
        this.lineIndex = lineIndex;
        this.offsets = Utf8Utf16Offsets.of(source.array(), source.length());
    }

    Utf8Utf16Offsets offsets() {
        return offsets;
    }

    LineIndex lineIndex() {
        return lineIndex;
    }

    Tree tree() {
        return tree;
    }

    /**
     * The buffer this tree was parsed from.
     *
     * <p>Valid only until the next reparse, which edits it in place — see {@link SourceBuffer}. It is
     * read at the start of the next reparse and nowhere else.
     */
    SourceBuffer source() {
        return source;
    }

    @Override
    public boolean hasError() {
        return tree.getRootNode().hasError();
    }

    @Override
    public List<ChangedRange> changedRangesSince(ParsedTree other) {
        List<Range> ranges = tree.getChangedRanges(((TreeSitterParsedTree) other).tree);
        List<ChangedRange> result = new ArrayList<>(ranges.size());
        for (Range range : ranges) {
            result.add(new ChangedRange(offsets.toUtf16(range.startByte()), offsets.toUtf16(range.endByte())));
        }
        return result;
    }

    @Override
    public SyntaxCursor cursor() {
        return new TreeSitterCursor(tree.walk(), offsets);
    }

    @Override
    public void close() {
        tree.close();
    }
}
