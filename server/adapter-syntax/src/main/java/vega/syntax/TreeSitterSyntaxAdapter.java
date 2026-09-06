package vega.syntax;

import io.github.treesitter.jtreesitter.InputEdit;
import io.github.treesitter.jtreesitter.InputEncoding;
import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.ParseCallback;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import io.github.treesitter.jtreesitter.Tree;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import vega.core.document.Edit;
import vega.core.document.LineIndex;
import vega.core.port.CancellationToken;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxParserPort;

/**
 * Outbound adapter over tree-sitter.
 *
 * <p>Two implementation choices here are budget-critical rather than stylistic, and both were
 * established by measurement (research D11):
 *
 * <ul>
 *   <li>Source is fed through {@link ParseCallback} in chunks. The {@code parse(String, …)}
 *       overload performs a full UTF-8 conversion and native copy on <em>every</em> call — an
 *       O(file size) cost per keystroke, which is exactly the whole-file work Principle VI forbids,
 *       hidden inside an innocuous API choice. The chunked path measured roughly fivefold faster.
 *   <li>The native libraries must be built with {@code -O3 -DNDEBUG}; see the build script.
 * </ul>
 *
 * <p>Not thread-safe: a {@link Parser} holds mutable state, so each instance belongs to one worker.
 */
public final class TreeSitterSyntaxAdapter implements SyntaxParserPort, AutoCloseable {

    /** Chunk size handed to tree-sitter per callback invocation. */
    private static final int CHUNK_BYTES = 4096;

    private final Arena arena = Arena.ofShared();
    private final Language language;
    private final Parser parser;

    public TreeSitterSyntaxAdapter() {
        this.language = Language.load(new BundledNativeLibraryLookup().get(arena), "tree_sitter_java");
        this.parser = new Parser(language);
    }

    @Override
    public ParsedTree parse(CharSequence text, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        SourceBuffer source = SourceBuffer.of(text.toString().getBytes(StandardCharsets.UTF_8));
        Tree tree =
                parser.parse(chunkedReader(source), InputEncoding.UTF_8, cancelWhen(cancellation))
                        .orElseThrow(() -> cancellation.isCancelled()
                                ? new vega.core.port.CancelledException()
                                : new IllegalStateException("tree-sitter returned no tree"));
        return new TreeSitterParsedTree(tree, source, LineIndex.of(source.array(), source.length()));
    }

    @Override
    public ParsedTree reparse(
            ParsedTree previous, Edit edit, CharSequence newText, CancellationToken cancellation) {
        cancellation.throwIfCancelled();

        TreeSitterParsedTree previousTree = (TreeSitterParsedTree) previous;
        Tree oldTree = previousTree.tree();

        // The edit arrives in UTF-16 code units, because that is what the core document and LSP both
        // use. Translate once, here, and work in bytes from this point on.
        Utf8Utf16Offsets oldOffsets = previousTree.offsets();
        int startByte = oldOffsets.toByte(edit.start());
        int oldEndByte = oldOffsets.toByte(edit.end());

        // Edited in place rather than spliced into a fresh array. Allocating per keystroke copied
        // the whole document every time; this shifts only the tail after the edit.
        long spliceStart = System.nanoTime();
        SourceBuffer source = previousTree.source();
        source.replace(startByte, oldEndByte, edit.newText().getBytes(StandardCharsets.UTF_8));
        ParseDiagnostics.SPLICE_NANOS.addAndGet(System.nanoTime() - spliceStart);
        // Editing the old tree first is what makes the reparse incremental: it shifts node
        // positions and marks the damaged span, so the new parse reuses everything untouched.
        long pointStart = System.nanoTime();
        int newEndByte = startByte + edit.newText().getBytes(StandardCharsets.UTF_8).length;
        LineIndex oldIndex = previousTree.lineIndex();
        LineIndex newIndex = oldIndex.edited(startByte, oldEndByte, source.array(), newEndByte);
        InputEdit inputEdit =
                new InputEdit(
                        startByte,
                        oldEndByte,
                        newEndByte,
                        new Point(oldIndex.rowAt(startByte), oldIndex.columnAt(startByte)),
                        new Point(oldIndex.rowAt(oldEndByte), oldIndex.columnAt(oldEndByte)),
                        new Point(newIndex.rowAt(newEndByte), newIndex.columnAt(newEndByte)));
        ParseDiagnostics.POINT_NANOS.addAndGet(System.nanoTime() - pointStart);
        oldTree.edit(inputEdit);

        long parseStart = System.nanoTime();
        Tree updated =
                parser.parse(chunkedReader(source), InputEncoding.UTF_8, oldTree, cancelWhen(cancellation))
                        .orElseThrow(() -> cancellation.isCancelled()
                                ? new vega.core.port.CancelledException()
                                : new IllegalStateException("tree-sitter returned no tree"));
        ParseDiagnostics.PARSE_NANOS.addAndGet(System.nanoTime() - parseStart);
        return new TreeSitterParsedTree(updated, source, newIndex);
    }

    /**
     * Feeds source to tree-sitter in chunks, aligned to UTF-8 character boundaries.
     *
     * <p>Slicing bytes naively would split a multi-byte character and corrupt the parse; the scan
     * back to a lead byte is what prevents it.
     */
    private static ParseCallback chunkedReader(SourceBuffer source) {
        return (int byteOffset, Point point) -> {
            int length = source.length();
            if (byteOffset >= length) {
                return "";
            }
            int end = Math.min(byteOffset + CHUNK_BYTES, length);
            while (end > byteOffset && end < length && isContinuationByte(source.byteAt(end))) {
                end--;
            }
            ParseDiagnostics.BYTES_READ.addAndGet(end - byteOffset);
            return source.decode(byteOffset, end);
        };
    }

    /**
     * Cancellation checkpoint consulted by tree-sitter during the parse itself.
     *
     * <p>This is what makes cancellation real rather than cosmetic: it abandons the parse mid-flight
     * and frees the worker, instead of running to completion and discarding the result. Under fast
     * typing nearly every parse is superseded, so reclaiming the thread is the whole point.
     */
    private static Parser.Options cancelWhen(CancellationToken cancellation) {
        return new Parser.Options(state -> cancellation.isCancelled());
    }

    /**
     * Applies an edit to the UTF-8 source without re-encoding the document.
     *
     * <p>A 1.4 MB array copy is a memcpy measured in microseconds; re-encoding the same text is
     * measured in milliseconds. Principle VI's "incremental everything" applies to the byte
     * representation too, not only to the parse tree.
     *
     * <p><b>Coordinate system:</b> {@link Edit} offsets are treated as UTF-8 byte offsets here. For
     * ASCII source — which Java overwhelmingly is — this coincides with character offsets. It does
     * NOT coincide for non-ASCII content, and LSP additionally speaks UTF-16 code units by default.
     * Reconciling those three coordinate systems is tracked as a spike finding; until then, edits
     * spanning non-ASCII text can mis-slice.
     */
    private static byte[] spliceUtf8(
            byte[] source, int startByte, int oldEndByte, String replacementText) {
        byte[] replacement = replacementText.getBytes(StandardCharsets.UTF_8);
        int start = Math.min(startByte, source.length);
        int end = Math.min(oldEndByte, source.length);

        byte[] result = new byte[source.length - (end - start) + replacement.length];
        System.arraycopy(source, 0, result, 0, start);
        System.arraycopy(replacement, 0, result, start, replacement.length);
        System.arraycopy(source, end, result, start + replacement.length, source.length - end);
        return result;
    }

    private static boolean isContinuationByte(byte b) {
        return (b & 0xC0) == 0x80;
    }

    /**
     * Builds the edit descriptor tree-sitter needs, with accurate row/column points.
     *
     * <p>Points are not decorative. tree-sitter's incremental algorithm reconciles them against the
     * points already stored in the reused tree, so supplying row 0 for everything — as a first cut
     * here did — makes them inconsistent with reality and forces far more of the tree to be rebuilt
     * than the edit warrants. The parse still re-reads almost no source, so the symptom is a slow
     * reparse with no obvious cause.
     */
    @Override
    public void close() {
        parser.close();
        arena.close();
    }
}
