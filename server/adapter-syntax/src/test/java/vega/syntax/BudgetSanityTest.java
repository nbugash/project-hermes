package vega.syntax;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import vega.core.document.Edit;
import vega.core.highlight.ChangedRange;
import vega.core.highlight.Highlighter;
import vega.core.highlight.Token;
import vega.core.port.CancellationToken;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;

/**
 * Fast signal on the spike's central question, ahead of the formal JMH benchmarks.
 *
 * <p>Thresholds here are deliberately loose — this runs on a shared machine with a cold JIT and is
 * not the budget gate. Its job is to catch an order-of-magnitude miss early; SC-004 and SC-005a are
 * enforced by JMH on a fixed runner.
 */
class BudgetSanityTest {

    private static final Path FIXTURE =
            Path.of("..", "..", "fixtures", "large-java-file", "Large.java");

    @Test
    void reportsParseAndReparseCostOnTheFixture() throws Exception {
        String source = Files.readString(FIXTURE);
        int lines = (int) source.lines().count();

        try (TreeSitterSyntaxAdapter adapter = new TreeSitterSyntaxAdapter()) {
            long parseStart = System.nanoTime();
            ParsedTree tree = adapter.parse(source, CancellationToken.never());
            double fullParseMs = (System.nanoTime() - parseStart) / 1_000_000.0;

            // Edit near the end of the file: the position a whole-file design would punish most.
            int editOffset =
                    Boolean.getBoolean("vega.editAtStart")
                            ? source.indexOf("int sum = values.size();")
                            : source.lastIndexOf("int sum = values.size();");
            assertTrue(editOffset > 0, "fixture must contain the expected anchor");

            List<Double> samples = new ArrayList<>();
            ParsedTree current = tree;
            String text = source;

            // Warm the JIT before measuring; the first iterations are dominated by FFM linkage
            // and compilation, which are not what SC-004 is about.
            for (int i = 0; i < 30; i++) {
                String warmEdited = text.substring(0, editOffset) + " " + text.substring(editOffset);
                ParsedTree warm =
                        adapter.reparse(current, Edit.replace(editOffset, editOffset, " ", i + 1),
                                warmEdited, CancellationToken.never());
                current.close();
                current = warm;
                text = warmEdited;
            }

            long bytesBefore = ParseDiagnostics.BYTES_READ.get();
            long spliceBefore = ParseDiagnostics.SPLICE_NANOS.get();
            long parseBefore = ParseDiagnostics.PARSE_NANOS.get();
            long pointBefore = ParseDiagnostics.POINT_NANOS.get();
            for (int i = 0; i < 200; i++) {
                String edited =
                        text.substring(0, editOffset) + " " + text.substring(editOffset);
                Edit edit = Edit.replace(editOffset, editOffset, " ", i + 1);

                long start = System.nanoTime();
                ParsedTree next = adapter.reparse(current, edit, edited, CancellationToken.never());
                samples.add((System.nanoTime() - start) / 1_000_000.0);

                current.close();
                current = next;
                text = edited;
            }

            // Highlight a viewport-sized window, the operation narrowing exists to keep cheap.
            long highlightStart = System.nanoTime();
            List<Token> windowTokens;
            try (SyntaxCursor cursor = current.cursor()) {
                windowTokens = new Highlighter().highlight(cursor, new ChangedRange(editOffset - 1200, editOffset + 1200));
            }
            double windowHighlightMs = (System.nanoTime() - highlightStart) / 1_000_000.0;

            current.close();

            long bytesPerReparse = (ParseDiagnostics.BYTES_READ.get() - bytesBefore) / 200;
            double spliceMs = (ParseDiagnostics.SPLICE_NANOS.get() - spliceBefore) / 200 / 1e6;
            double parseMs = (ParseDiagnostics.PARSE_NANOS.get() - parseBefore) / 200 / 1e6;
            double pointMs = (ParseDiagnostics.POINT_NANOS.get() - pointBefore) / 200 / 1e6;

            Collections.sort(samples);
            double p50 = samples.get(samples.size() / 2);
            double p95 = samples.get((int) (samples.size() * 0.95));

            System.out.printf(
                    "%n=== BUDGET SANITY (%d lines, %d bytes) ===%n"
                            + "full parse        : %.1f ms   (SC-005a budget 500 ms)%n"
                            + "reparse p50       : %.3f ms%n"
                            + "reparse p95       : %.3f ms  (SC-004 budget 5 ms)%n"
                            + "window highlight  : %.3f ms   (%d tokens)%n"
                            + "bytes re-read/edit: %d   (document is %d bytes)%n"
                            + "  of which splice  : %.3f ms%n"
                            + "  of which points  : %.3f ms%n"
                            + "  of which parse   : %.3f ms%n",
                    lines, source.length(), fullParseMs, p50, p95, windowHighlightMs, windowTokens.size(),
                    bytesPerReparse, source.length(), spliceMs, pointMs, parseMs);

            assertTrue(fullParseMs < 5000, "full parse wildly over budget: " + fullParseMs + " ms");
            assertTrue(p95 < 50, "reparse p95 wildly over budget: " + p95 + " ms");
        }
    }
}
