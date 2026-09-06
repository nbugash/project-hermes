plugins {
    id("me.champeau.jmh") version "0.7.3"
}

dependencies {
    implementation(project(":server:core"))
    // Pinned exactly, not by range. Error recovery is undocumented behaviour that has changed
    // across tree-sitter minor versions before, and SC-006 (broken code degrades locally) depends on
    // it. DamageLocalityTest guards the behaviour; this pin makes a change to it a deliberate act
    // rather than something that arrives with a routine dependency bump. Native core and grammar
    // versions are pinned to match in native/build-native.sh.
    implementation("io.github.tree-sitter:jtreesitter:0.26.1")
}

// jtreesitter is an FFM binding; restricted native access must be enabled explicitly. It defaults
// to a warning today but is documented to become a hard denial, so it is set now rather than later.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // NativePackagingTest executes the native build script, so a change to it must re-run the tests.
    // Without this Gradle sees no Java change, skips the task, and the test reports a pass for a
    // script it never looked at — the same stale-artifact trap as a build whose output is discarded.
    inputs.file("native/build-native.sh").withPathSensitivity(PathSensitivity.RELATIVE)
    // The budget tests hold a multi-megabyte document and churn a fresh copy of it per sampled
    // edit; the default 512 MB test heap runs out partway through a multi-position run.
    maxHeapSize = "2g"
}

jmh {
    jmhVersion = "1.37"
    resultFormat = "JSON"

    // Explicit counts, matching the annotations: JMH's defaults take minutes per benchmark, which
    // makes the pull-request gate unusable. See the note in server/core/build.gradle.kts.
    fork = 1
    warmupIterations = 3
    iterations = 5
    // The adapter loads native libraries through the FFM API, exactly as the tests and app do.
    jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}


/**
 * Fails unless every benchmark actually produced a result.
 *
 * JMH catches a benchmark's setup exception, reports it in its own output and exits zero — and
 * `failOnError` does not cover it. A CI run once assembled a zero-byte corpus, measured nothing and
 * went green. Checking the results file asserts the outcome rather than trusting the tool to report
 * its own failure, which is the same reason the budget gate compares numbers instead of exit codes.
 */
val expectedBenchmarks = listOf(
    "insertOneCharacter",
    "insertOneCharacterWhileBroken",
    "applyEditToMirror",
    "tokenizeWholeDocument",
)

val verifyBenchmarksRan = tasks.register("verifyBenchmarksRan") {
    description = "Fail if any benchmark is missing from the JMH results"
    group = "verification"
    val results = layout.buildDirectory.file("results/jmh/results.json")
    inputs.file(results)

    doLast {
        val file = results.get().asFile
        require(file.exists()) { "No JMH results at ${file.path}; the benchmarks did not run." }

        val text = file.readText()
        val missing = expectedBenchmarks.filterNot { text.contains("\"$it\"") || text.contains(".$it\"") }
        require(missing.isEmpty()) {
            "Benchmarks produced no result: $missing. A benchmark that cannot measure must fail the " +
                "build rather than report an empty table."
        }
        logger.lifecycle("verified ${expectedBenchmarks.size} benchmarks produced results")
    }
}

tasks.named("jmh") { finalizedBy(verifyBenchmarksRan) }
