package vega.fixtures

import java.security.MessageDigest
import kotlin.random.Random

/**
 * Deterministic generator for the reference Java fixture.
 *
 * Research measured tree-sitter against a uniform, ASCII-only file and flagged that as a caveat:
 * real Java with generics, annotations, lambdas and text blocks may parse more slowly and recover
 * from syntax errors differently. This generator therefore emits varied constructs rather than a
 * single repeated shape, so the fixture is a fairer proxy. It remains synthetic; task T097 still
 * requires re-measuring against a real corpus before any number becomes a CI baseline.
 */
object LargeJavaFileGenerator {

    fun generate(targetLines: Int): String {
        val random = Random(seed = 20260905L)
        val out = StringBuilder(targetLines * 40)
        out.appendLine("package vega.fixtures.large;")
        out.appendLine()
        out.appendLine("import java.util.List;")
        out.appendLine("import java.util.Map;")
        out.appendLine("import java.util.Optional;")
        out.appendLine("import java.util.function.Function;")
        out.appendLine("import java.util.stream.Collectors;")
        out.appendLine()
        out.appendLine("/** Generated fixture. Do not edit by hand; see fixtures/build.gradle.kts. */")
        out.appendLine("public final class Large {")
        out.appendLine()

        var index = 0
        while (out.lineCount() < targetLines - 2) {
            emitClass(out, index, random)
            index++
        }

        while (out.lineCount() < targetLines - 1) {
            out.appendLine("    // padding to reach an exact line count")
        }
        out.appendLine("}")
        return out.toString()
    }

    private fun emitClass(out: StringBuilder, index: Int, random: Random) {
        val name = "Node$index"
        out.appendLine("    /** Fixture type $index. */")
        out.appendLine("    public static final class $name<T extends Comparable<T>> {")
        out.appendLine("        private final List<T> values;")
        out.appendLine("        private final Map<String, Integer> counts;")
        out.appendLine("        private static final String LABEL = \"node-$index\";")
        out.appendLine()
        out.appendLine("        public $name(List<T> values, Map<String, Integer> counts) {")
        out.appendLine("            this.values = values;")
        out.appendLine("            this.counts = counts;")
        out.appendLine("        }")
        out.appendLine()

        repeat(2 + random.nextInt(3)) { m ->
            when (random.nextInt(4)) {
                0 -> emitStreamMethod(out, m)
                1 -> emitBranchMethod(out, m)
                2 -> emitTextBlockMethod(out, m, index)
                else -> emitAnnotatedMethod(out, m)
            }
            out.appendLine()
        }

        out.appendLine("    }")
        out.appendLine()
    }

    private fun emitStreamMethod(out: StringBuilder, m: Int) {
        out.appendLine("        public List<String> mapped$m(Function<T, String> mapper) {")
        out.appendLine("            return values.stream()")
        out.appendLine("                .map(mapper)")
        out.appendLine("                .filter(s -> !s.isBlank())")
        out.appendLine("                .collect(Collectors.toList());")
        out.appendLine("        }")
    }

    private fun emitBranchMethod(out: StringBuilder, m: Int) {
        out.appendLine("        public Optional<T> pick$m(int bound) {")
        out.appendLine("            if (values.isEmpty()) {")
        out.appendLine("                return Optional.empty();")
        out.appendLine("            }")
        out.appendLine("            for (T value : values) {")
        out.appendLine("                if (counts.getOrDefault(LABEL, 0) > bound) {")
        out.appendLine("                    return Optional.of(value);")
        out.appendLine("                }")
        out.appendLine("            }")
        out.appendLine("            return Optional.of(values.get(0));")
        out.appendLine("        }")
    }

    private fun emitTextBlockMethod(out: StringBuilder, m: Int, index: Int) {
        out.appendLine("        public String describe$m() {")
        out.appendLine("            return \"\"\"")
        out.appendLine("                node $index, method $m")
        out.appendLine("                values: %d")
        out.appendLine("                \"\"\".formatted(values.size());")
        out.appendLine("        }")
    }

    private fun emitAnnotatedMethod(out: StringBuilder, m: Int) {
        out.appendLine("        @SafeVarargs")
        out.appendLine("        public final int total$m(T... extra) {")
        out.appendLine("            int sum = values.size();")
        out.appendLine("            for (T ignored : extra) {")
        out.appendLine("                sum += 1;")
        out.appendLine("            }")
        out.appendLine("            return sum;")
        out.appendLine("        }")
    }

    private fun StringBuilder.lineCount(): Int = count { it == '\n' }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
