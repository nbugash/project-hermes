plugins {
    `java-library`
}

dependencies {
    implementation(project(":server:core"))
    implementation(project(":protocol:java"))

    // lsp4j is part of this module's public API, not an implementation detail: VegaLanguageServer
    // implements LanguageServer and its handlers return lsp4j types, so anything wiring this module
    // needs those types on its own compile classpath.
    api("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
}
