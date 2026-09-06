// Generates the reference 50,000-line Java file that every performance budget is measured
// against. Deterministic by construction: a fixed seed and no timestamps, so the digest is
// stable across machines. Changing this generator voids every recorded benchmark baseline.
val fixtureDir = layout.projectDirectory.dir("large-java-file")
val fixtureFile = fixtureDir.file("Large.java")

tasks.register("generateLargeJavaFile") {
    description = "Generate the deterministic 50,000-line Java fixture"
    group = "fixtures"
    outputs.file(fixtureFile)

    doLast {
        val target = fixtureFile.asFile
        target.parentFile.mkdirs()
        target.writeText(vega.fixtures.LargeJavaFileGenerator.generate(targetLines = 50_000))
        logger.lifecycle("Wrote ${target.length()} bytes to ${target.path}")
    }
}

tasks.register("verifyFixtureDigest") {
    description = "Fail if the fixture no longer matches its recorded digest"
    group = "fixtures"

    doLast {
        val target = fixtureFile.asFile
        require(target.exists()) { "Fixture missing. Run :fixtures:generateLargeJavaFile" }
        val recorded = fixtureDir.file("CHECKSUM").asFile
        require(recorded.exists()) { "CHECKSUM missing. Run :fixtures:recordFixtureDigest" }
        val actual = vega.fixtures.LargeJavaFileGenerator.sha256(target.readBytes())
        val expected = recorded.readText().trim().substringBefore(' ')
        require(actual == expected) {
            "Fixture digest changed ($actual != $expected). Benchmark baselines are void until " +
                "re-established; see specs/001-large-file-responsiveness/data-model.md."
        }
        logger.lifecycle("Fixture digest OK: $actual")
    }
}

tasks.register("recordFixtureDigest") {
    description = "Record the fixture digest after a deliberate regeneration"
    group = "fixtures"

    doLast {
        val target = fixtureFile.asFile
        val digest = vega.fixtures.LargeJavaFileGenerator.sha256(target.readBytes())
        fixtureDir.file("CHECKSUM").asFile.writeText("$digest  Large.java\n")
        logger.lifecycle("Recorded digest $digest")
    }
}
