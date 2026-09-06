plugins {
    application
}

dependencies {
    implementation(project(":server:core"))
    implementation(project(":server:adapter-lsp"))
    implementation(project(":server:adapter-syntax"))
    implementation(project(":server:adapter-fs"))
    implementation(project(":protocol:java"))
    implementation("io.vertx:vertx-core:5.0.0")

    implementation("com.google.dagger:dagger:2.52")
    annotationProcessor("com.google.dagger:dagger-compiler:2.52")
}

application {
    mainClass.set("vega.app.Main")
    // Required by the FFM-based syntax adapter; see server/adapter-syntax/build.gradle.kts.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
