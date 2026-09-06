package vega.syntax;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vega.core.document.Edit;
import vega.core.port.CancellationToken;
import vega.core.port.ParsedTree;

/**
 * T097: re-measures the reparse budget against real production Java rather than generated source.
 *
 * <p>The synthetic fixture nests hundreds of classes inside a single outer class. Real Java does not
 * look like that — it is mostly shallow, with many sibling top-level types — and the spike's SC-004
 * result proved sensitive to structural differences of exactly this kind. Measuring both shapes is
 * the point: the gap between them is the answer to whether 6.2 ms is a property of Java parsing or
 * of one unrepresentative file.
 *
 * <p>Skipped when the corpus is absent, since it is assembled from the local Gradle cache by
 * {@code fixtures/large-java-file/build-real-corpus.sh} and is not checked in.
 */
class RealCorpusBudgetTest {

    private static final Path CORPUS =
            Path.of("..", "..", "fixtures", "large-java-file", "RealCorpus.java");

    private static final int WARMUP = 30;
    private static final int SAMPLES = 200;

    @Test
    void reportsParseAndReparseCostOnRealJava() throws Exception {
        Assumptions.assumeTrue(
                Files.exists(CORPUS), "RealCorpus.java absent; run build-real-corpus.sh to measure");

        String source = Files.readString(CORPUS);
        int lines = (int) source.lines().count();

        try (TreeSitterSyntaxAdapter adapter = new TreeSitterSyntaxAdapter()) {
            long parseStart = System.nanoTime();
            adapter.parse(source, CancellationToken.never()).close();
            double fullParseMs = (System.nanoTime() - parseStart) / 1_000_000.0;

            System.out.printf(
                    "%n=== T097 REAL CORPUS (%d lines, %d bytes) ===%n"
                            + "full parse        : %.1f ms   (SC-005a budget 500 ms)%n",
                    lines, source.length(), fullParseMs);

            // SC-004 says typing anywhere, so one anchor is not enough to confirm it: a single
            // lucky position would hide a structural worst case. Measure near the start, the middle
            // and the end, and judge the budget on the worst of the three.
            // Anchored on indented `return` statements so every edit lands in real code. The bare
            // string "return " also matches javadoc `@return` tags, and an edit inside a comment
            // reparses far more cheaply than one in code — measuring there flatters the result.
            int[] anchors = {
                source.indexOf("\n        return ") + 9,
                source.indexOf("\n        return ", source.length() / 2) + 9,
                source.lastIndexOf("\n        return ") + 9
            };
            String[] anchorNames = {"start", "middle", "end"};
            for (int a = 0; a < anchors.length; a++) {
                assertTrue(anchors[a] > 0, "corpus must contain an anchor near the " + anchorNames[a]);
            }

            double worstP95 = 0;
            for (int a = 0; a < anchors.length; a++) {
                worstP95 = Math.max(worstP95, measureAt(adapter, source, anchors[a], anchorNames[a], lines));
            }

            System.out.printf(
                    "%nT097 VERDICT: worst-position reparse p95 = %.3f ms against a 5 ms budget -> %s%n",
                    worstP95, worstP95 <= 5.0 ? "SC-004 MET" : "SC-004 MISSED");
        }
    }

    private double measureAt(
            TreeSitterSyntaxAdapter adapter, String source, int editOffset, String label, int lines) {
        {
            ParsedTree tree = adapter.parse(source, CancellationToken.never());

            ParsedTree current = tree;
            String text = source;

            for (int i = 0; i < WARMUP; i++) {
                String edited = text.substring(0, editOffset) + " " + text.substring(editOffset);
                ParsedTree next =
                        adapter.reparse(
                                current,
                                Edit.replace(editOffset, editOffset, " ", i + 1),
                                edited,
                                CancellationToken.never());
                current.close();
                current = next;
                text = edited;
            }

            long bytesBefore = ParseDiagnostics.BYTES_READ.get();
            long spliceBefore = ParseDiagnostics.SPLICE_NANOS.get();
            long parseBefore = ParseDiagnostics.PARSE_NANOS.get();

            List<Double> samples = new ArrayList<>();
            for (int i = 0; i < SAMPLES; i++) {
                String edited = text.substring(0, editOffset) + " " + text.substring(editOffset);
                Edit edit = Edit.replace(editOffset, editOffset, " ", WARMUP + i + 1);

                long start = System.nanoTime();
                ParsedTree next = adapter.reparse(current, edit, edited, CancellationToken.never());
                samples.add((System.nanoTime() - start) / 1_000_000.0);

                current.close();
                current = next;
                text = edited;
            }
            current.close();

            long bytesPerReparse = (ParseDiagnostics.BYTES_READ.get() - bytesBefore) / SAMPLES;
            double spliceMs =
                    (ParseDiagnostics.SPLICE_NANOS.get() - spliceBefore) / SAMPLES / 1e6;
            double parseMs = (ParseDiagnostics.PARSE_NANOS.get() - parseBefore) / SAMPLES / 1e6;

            Collections.sort(samples);
            double p50 = samples.get(SAMPLES / 2);
            double p95 = samples.get((int) (SAMPLES * 0.95));

            System.out.printf(
                    "  edit at %-7s: p50 %.3f ms   p95 %.3f ms   splice %.3f   parse %.3f   reread %d B%n",
                    label, p50, p95, spliceMs, parseMs, bytesPerReparse);
            return p95;
        }
    }
}
