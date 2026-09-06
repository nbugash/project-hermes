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
 * SC-004 while the document is invalid.
 *
 * <p>A developer types inside broken code for as long as it takes to finish the thought — an
 * unclosed brace can live for a minute of continuous editing. If reparse degrades in that state, the
 * budget is missed for precisely the stretch where typing is most active, and every measurement
 * taken on valid code would have missed it.
 *
 * <p>The error is introduced once in setup and never repaired, so every measured iteration reparses
 * a tree that is already in an error state.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ErrorStateReparseBenchmark {

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
                    "RealCorpus.java is absent; run fixtures/large-java-file/build-real-corpus.sh");
        }
        adapter = new TreeSitterSyntaxAdapter();
        text = Files.readString(CORPUS);
        editOffset = codeAnchor(text);
        version = 1;

        ParsedTree healthy = adapter.parse(text, CancellationToken.never());

        // An unmatched *closing* brace, not an opening one. A stray '{' is recovered as a block
        // whose closer is merely missing, which tree-sitter handles without flagging an error at
        // all — the guard below caught exactly that. A stray '}' closes an enclosing block early and
        // is unambiguously invalid.
        String broken = text.substring(0, editOffset) + "}" + text.substring(editOffset);
        tree =
                adapter.reparse(
                        healthy, Edit.replace(editOffset, editOffset, "}", version++), broken, CancellationToken.never());
        text = broken;

        mirror = vega.core.document.Document.opened("file:///corpus.java", text);

        if (!tree.hasError()) {
            throw new IllegalStateException("fixture did not enter an error state; the benchmark would measure nothing");
        }
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

    @Benchmark
    public ParsedTree insertOneCharacterWhileBroken() {
        // Mirror update plus reparse, matching IncrementalReparseBenchmark exactly so the healthy
        // and broken numbers are comparable. Measuring different work in the two would make the
        // error-recovery cost look like whichever difference the harness happened to introduce.
        mirror = mirror.apply(
                vega.core.document.Edit.replace(editOffset, editOffset, " ", mirror.version()));

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
