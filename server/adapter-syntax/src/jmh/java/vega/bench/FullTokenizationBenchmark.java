package vega.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import vega.core.highlight.ChangedRange;
import vega.core.highlight.Highlighter;
import vega.core.highlight.SemanticTokenEncoder;
import vega.core.highlight.Token;
import vega.core.port.CancellationToken;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;
import vega.syntax.TreeSitterSyntaxAdapter;

/**
 * SC-005b: tokenizing the whole 50,000-line document, under 800 ms.
 *
 * <p>This is the background fill that follows the first viewport paint (T064), so it is allowed to
 * be slow in a way the per-keystroke path is not — but it competes for the same worker pool, and a
 * fill that overruns keeps arriving late for the whole session.
 *
 * <p>Both halves are measured: walking the tree and encoding the result. Benchmarking only the walk
 * would miss that the encoder scans the text to convert offsets into line/column pairs.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class FullTokenizationBenchmark {

    private static final List<String> LEGEND =
            List.of("keyword", "type", "function", "variable", "string", "number", "comment", "operator");

    private static final Path CORPUS =
            Path.of("..", "..", "fixtures", "large-java-file", "RealCorpus.java");

    private TreeSitterSyntaxAdapter adapter;
    private ParsedTree tree;
    private String text;
    private ChangedRange wholeDocument;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        if (!Files.exists(CORPUS)) {
            throw new IllegalStateException(
                    "RealCorpus.java is absent; run fixtures/large-java-file/build-real-corpus.sh");
        }
        adapter = new TreeSitterSyntaxAdapter();
        text = Files.readString(CORPUS);
        tree = adapter.parse(text, CancellationToken.never());
        wholeDocument = new ChangedRange(0, text.length());
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
    public int tokenizeWholeDocument() {
        List<Token> tokens;
        try (SyntaxCursor cursor = tree.cursor()) {
            tokens = new Highlighter().highlight(cursor, wholeDocument, CancellationToken.never());
        }
        return SemanticTokenEncoder.encode(text, tokens, LEGEND).length;
    }
}
