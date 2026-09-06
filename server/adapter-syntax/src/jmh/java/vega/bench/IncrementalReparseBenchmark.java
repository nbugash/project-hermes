package vega.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import vega.core.document.Edit;
import vega.core.port.CancellationToken;
import vega.core.port.ParsedTree;
import vega.syntax.TreeSitterSyntaxAdapter;

/**
 * SC-004: single-character re-analysis of a 50,000-line Java file, p95 under 5 ms.
 *
 * <p>Measured against {@code RealCorpus.java} — real production Java — because ADR-0002 established
 * that reparse cost tracks tree shape, not file size, and the synthetic fixture's single enormous
 * outer class is not a shape real code takes. Benchmarking the synthetic file would gate CI on a
 * number no user experience produces.
 *
 * <p>Iteration counts are explicit. JMH's defaults took 8m30s for a trivial benchmark on the
 * reference machine, which makes a pull-request gate unusable.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class IncrementalReparseBenchmark {

    private static final Path CORPUS =
            Path.of("..", "..", "fixtures", "large-java-file", "RealCorpus.java");

    private TreeSitterSyntaxAdapter adapter;
    private ParsedTree tree;
    private String text;
    private int editOffset;
    private int version;
    private vega.core.document.Document mirror;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        // Existence is not enough: an empty file exists. A CI run once assembled a zero-byte
        // corpus, measured nothing, and reported success — a benchmark that cannot measure must
        // fail loudly rather than print an empty table.
        if (!Files.exists(CORPUS) || Files.size(CORPUS) < 1_000_000) {
            throw new IllegalStateException(
                    "RealCorpus.java is absent; run fixtures/large-java-file/build-real-corpus.sh. "
                            + "Benchmarking the synthetic fixture instead would gate on the wrong number.");
        }
        adapter = new TreeSitterSyntaxAdapter();
        text = Files.readString(CORPUS);
        editOffset = codeAnchor(text);
        version = 1;
        tree = adapter.parse(text, CancellationToken.never());
        mirror = vega.core.document.Document.opened("file:///corpus.java", text);
    }


    /**
     * An offset inside real code, not inside a comment.
     *
     * <p>The obvious anchor — the last {@code "return "} in the file — lands in a javadoc block
     * ({@code * @return ...}) in this corpus. Edits inside a comment are structurally insignificant
     * and reparse far more cheaply than edits in code, so anchoring there measures the wrong thing
     * and reports a number the product never achieves. Requiring the statement indentation selects
     * a real {@code return} statement instead.
     */
    private static int codeAnchor(String text) {
        int anchor = text.lastIndexOf("\n        return ");
        if (anchor < 0) {
            throw new IllegalStateException("corpus contains no indented return statement to anchor on");
        }
        return anchor + "\n        ".length();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (tree != null) {
            tree.close();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    /**
     * Updating the mirror, with no parsing at all.
     *
     * <p>Measures {@code Document.apply} itself rather than a stand-in for it. The earlier version of
     * this benchmark timed a raw {@code substring + concat}, which was what the implementation did at
     * the time; measuring the real call keeps it honest when the representation changes underneath.
     */
    @Benchmark
    public vega.core.document.Document applyEditToMirror() {
        vega.core.document.Document next =
                mirror.apply(
                        vega.core.document.Edit.replace(editOffset, editOffset, " ", mirror.version()));
        mirror = next;
        return next;
    }

    /**
     * One keystroke exactly as the product performs it: update the mirror, then reparse.
     *
     * <p>Both steps are inside the measured region because the {@code didChange} handler does both.
     * An earlier version of this benchmark built the new text with {@code substring + concat}, which
     * is what the implementation did at the time; keeping that after the representation changed
     * would have measured a copy the product no longer makes and understated the improvement.
     */
    @Benchmark
    public ParsedTree insertOneCharacter() {
        Edit edit = Edit.replace(editOffset, editOffset, " ", mirror.version());
        mirror = mirror.apply(edit);

        ParsedTree next =
                adapter.reparse(
                        tree,
                        Edit.replace(editOffset, editOffset, " ", version++),
                        mirror.content(),
                        CancellationToken.never());
        tree.close();
        tree = next;
        return next;
    }
}
