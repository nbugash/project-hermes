package vega.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import vega.core.highlight.ChangedRange;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;

/**
 * Range requests are what keep whole-file tokenization off the cold-start path (research D3): the
 * editor asks for the viewport it is about to paint, not the document. A handler that quietly
 * tokenized everything would still return correct tokens, so the assertions here are about what is
 * <em>absent</em> from the response as much as what is present.
 */
class SemanticTokensRangeTest {

    private static final String SOURCE =
            "class A {\n" // line 0
                    + "    int x = 1;\n" // line 1
                    + "    int y = 2;\n" // line 2
                    + "}\n"; // line 3

    /** Leaf nodes as tree-sitter would report them, at UTF-16 offsets into SOURCE. */
    private static ParsedTree treeFor(String source) {
        List<FakeNode> nodes =
                List.of(
                        new FakeNode("class", source.indexOf("class"), source.indexOf("class") + 5),
                        new FakeNode("type_identifier", source.indexOf("A"), source.indexOf("A") + 1),
                        new FakeNode("int", source.indexOf("int"), source.indexOf("int") + 3),
                        new FakeNode("identifier", source.indexOf("x"), source.indexOf("x") + 1),
                        new FakeNode(
                                "decimal_integer_literal", source.indexOf("1"), source.indexOf("1") + 1),
                        new FakeNode("int", source.lastIndexOf("int"), source.lastIndexOf("int") + 3),
                        new FakeNode("identifier", source.indexOf("y"), source.indexOf("y") + 1),
                        new FakeNode(
                                "decimal_integer_literal", source.indexOf("2"), source.indexOf("2") + 1));
        return new FakeTree(nodes);
    }

    private static SemanticTokensRangeParams rangeOf(int startLine, int endLine) {
        SemanticTokensRangeParams params = new SemanticTokensRangeParams();
        params.setTextDocument(new TextDocumentIdentifier("file:///A.java"));
        params.setRange(new Range(new Position(startLine, 0), new Position(endLine, 0)));
        return params;
    }

    @Test
    void returnsOnlyTokensInsideTheRequestedRange() throws Exception {
        SemanticTokensHandler handler = new SemanticTokensHandler(VegaLanguageServer.TokenLegend.TYPES);

        SemanticTokens tokens =
                handler.range(SOURCE, treeFor(SOURCE), rangeOf(1, 2)).get();

        // Line 1 holds `int x = 1;` — three tokens, and nothing from line 0 or line 2.
        assertEquals(3 * 5, tokens.getData().size(), "expected exactly the three tokens on line 1");

        // The first token's deltaLine is measured from the document origin, so line 1 means 1.
        assertEquals(1, tokens.getData().get(0), "first token should be on line 1");
    }

    @Test
    void encodesRelativePositionsAgainstTheDocumentNotTheRange() throws Exception {
        SemanticTokensHandler handler = new SemanticTokensHandler(VegaLanguageServer.TokenLegend.TYPES);

        SemanticTokens tokens = handler.range(SOURCE, treeFor(SOURCE), rangeOf(2, 3)).get();

        // Anchoring deltas to the range instead of the document is the classic range-request bug:
        // every token paints one viewport too high, and only on scrolled views.
        assertEquals(2, tokens.getData().get(0), "deltaLine must be absolute for the first token");
    }

    @Test
    void emptyRangeYieldsNoTokensRatherThanTheWholeDocument() throws Exception {
        SemanticTokensHandler handler = new SemanticTokensHandler(VegaLanguageServer.TokenLegend.TYPES);

        SemanticTokens tokens = handler.range(SOURCE, treeFor(SOURCE), rangeOf(3, 3)).get();

        assertTrue(tokens.getData().isEmpty(), "a zero-height range must not fall back to full file");
    }

    private record FakeNode(String type, int start, int end) {}

    /** Flat tree: a root whose children are the leaves, which is all the Highlighter walks. */
    private record FakeTree(List<FakeNode> nodes) implements ParsedTree {
        @Override
        public boolean hasError() {
            return false;
        }

        @Override
        public List<ChangedRange> changedRangesSince(ParsedTree other) {
            return List.of();
        }

        @Override
        public SyntaxCursor cursor() {
            return new FakeCursor(nodes);
        }

        @Override
        public void close() {}
    }

    private static final class FakeCursor implements SyntaxCursor {
        private final List<FakeNode> nodes;
        private int index = -1;

        FakeCursor(List<FakeNode> nodes) {
            this.nodes = nodes;
        }

        @Override
        public boolean gotoFirstChild() {
            if (index >= 0) {
                return false;
            }
            index = 0;
            return true;
        }

        @Override
        public boolean gotoNextSibling() {
            if (index < 0 || index >= nodes.size() - 1) {
                return false;
            }
            index++;
            return true;
        }

        @Override
        public boolean gotoParent() {
            index = -1;
            return true;
        }

        @Override
        public String nodeType() {
            return index < 0 ? "program" : nodes.get(index).type();
        }

        @Override
        public int startOffset() {
            return index < 0 ? 0 : nodes.get(index).start();
        }

        @Override
        public int endOffset() {
            return index < 0 ? Integer.MAX_VALUE : nodes.get(index).end();
        }

        @Override
        public boolean isErrorNode() {
            return false;
        }

        @Override
        public void close() {}
    }
}
