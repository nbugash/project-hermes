plugins {
    id("me.champeau.jmh") version "0.7.3"
}

// The analysis core deliberately has NO production dependencies. Constitution Principle III
// forbids it importing Vert.x, lsp4j, jOOQ, SQLite or tree-sitter; the cheapest way to guarantee
// that is to not put them on its compile classpath at all. ArchUnit then catches anything that
// sneaks in via a transitive test dependency.
dependencies {
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")

    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

jmh {
    jmhVersion = "1.37"
    resultFormat = "JSON"
    // Default output is build/results/jmh/results.json; no override, so the CI gate and a local
    // run read the same path.
    //
    // Default fork/iteration counts took 8m30s for a trivial benchmark on this machine. The real
    // budget benchmarks must set explicit, tighter counts or the PR gate becomes unusable.
    // generatorType is left at its reflection default on purpose: JMH 1.37 pins ASM 9.0, which
    // cannot read Java 17+ class files, let alone Java 25. Setting it to "asm" breaks the build.
}
