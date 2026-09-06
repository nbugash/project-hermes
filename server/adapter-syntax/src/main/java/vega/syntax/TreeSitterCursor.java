package vega.syntax;

import io.github.treesitter.jtreesitter.TreeCursor;
import vega.core.port.SyntaxCursor;

/**
 * Lazy traversal over an adapter-owned tree.
 *
 * <p>Deliberately a moving pointer rather than a snapshot: materialising a translated tree per
 * request would cost the whole-file work the incremental design exists to avoid.
 */
final class TreeSitterCursor implements SyntaxCursor {

    private final TreeCursor cursor;
    private final Utf8Utf16Offsets offsets;

    TreeSitterCursor(TreeCursor cursor, Utf8Utf16Offsets offsets) {
        this.cursor = cursor;
        this.offsets = offsets;
    }

    @Override
    public boolean gotoFirstChild() {
        return cursor.gotoFirstChild();
    }

    @Override
    public boolean gotoNextSibling() {
        return cursor.gotoNextSibling();
    }

    @Override
    public boolean gotoParent() {
        return cursor.gotoParent();
    }

    @Override
    public String nodeType() {
        return cursor.getCurrentNode().getType();
    }

    /**
     * tree-sitter counts UTF-8 bytes; everything on the core side of this port counts UTF-16 code
     * units. Translating here rather than at the call sites keeps the two-unit problem inside the
     * adapter, and costs nothing on the ASCII documents that dominate source code.
     */
    @Override
    public int startOffset() {
        return offsets.toUtf16(cursor.getCurrentNode().getStartByte());
    }

    @Override
    public int endOffset() {
        return offsets.toUtf16(cursor.getCurrentNode().getEndByte());
    }

    @Override
    public boolean isErrorNode() {
        var node = cursor.getCurrentNode();
        return node.isError() || node.isMissing();
    }

    @Override
    public void close() {
        cursor.close();
    }
}
