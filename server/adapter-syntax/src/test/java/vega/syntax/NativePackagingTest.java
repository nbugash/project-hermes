package vega.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Platform packaging: what can be checked without the platform.
 *
 * <p>Nothing here proves the macOS build works. Whether {@code clang -dynamiclib} produces a loadable
 * {@code .dylib}, and whether the FFM linker opens it, are the real operating system doing real work
 * — a test that mocked them would only assert this code's own assumptions back at it. Verifying that
 * needs a macOS runner, and is tracked as T098.
 *
 * <p>What is checkable anywhere is the pair of *decisions*: which file names the loader will look
 * for, and which file names the build script will produce. Those were hardcoded to {@code .so} until
 * someone tried to install on a Mac, and the mismatch would have failed at run time with a
 * missing-symbol error that never mentions the extension.
 */
class NativePackagingTest {

    private static final Path BUILD_SCRIPT = Path.of("native", "build-native.sh");

    @Test
    void theLoaderLooksForTheNamesThisPlatformActuallyUses() {
        // System.mapLibraryName is the platform's own answer, so this asserts the loader defers to it
        // rather than guessing: libtree-sitter.so on Linux, .dylib on macOS, .dll on Windows.
        assertEquals(System.mapLibraryName("tree-sitter"), BundledNativeLibraryLookup.libraryNames().get(0));
        assertEquals(
                System.mapLibraryName("tree-sitter-java"), BundledNativeLibraryLookup.libraryNames().get(1));
    }

    @Test
    void everyLibraryTheLoaderExpectsIsActuallyBundled() throws IOException {
        // The failure this catches: a build that produced .so while the loader wants .dylib, or the
        // reverse. On macOS this test fails immediately rather than at the first parse.
        for (String name : BundledNativeLibraryLookup.libraryNames()) {
            var resource = BundledNativeLibraryLookup.class.getResourceAsStream("/native/" + name);
            assertNotNull(
                    resource,
                    "no bundled native library /native/" + name + " — run native/build-native.sh");
            resource.close();
        }
    }

    @Test
    void theBuildScriptTargetsThisPlatformsConvention() throws Exception {
        Map<String, String> target = runBuildScriptTarget(Map.of());

        assertEquals(
                System.mapLibraryName("tree-sitter"),
                "lib" + "tree-sitter." + target.get("ext"),
                "the script would build a name the loader does not look for");
    }

    @Test
    void theBuildScriptWouldProduceADylibOnMacOs() throws Exception {
        // uname is stubbed rather than the platform mocked. This asserts the branch is reachable and
        // chooses the right convention — not that the resulting library loads, which needs a Mac.
        Map<String, String> target = runBuildScriptTarget(Map.of("uname", "Darwin"));

        assertEquals("Darwin", target.get("os"));
        assertEquals("dylib", target.get("ext"));
        assertTrue(
                target.get("cflags").contains("-dynamiclib"),
                "macOS needs -dynamiclib; -shared produces something the loader will not open: "
                        + target.get("cflags"));
    }

    @Test
    void theBuildScriptFallsBackToSharedObjectsElsewhere() throws Exception {
        Map<String, String> target = runBuildScriptTarget(Map.of("uname", "Linux"));

        assertEquals("so", target.get("ext"));
        assertTrue(target.get("cflags").contains("-shared"));
    }

    /** Runs the build script's target-reporting mode, optionally with a stubbed {@code uname}. */
    private static Map<String, String> runBuildScriptTarget(Map<String, String> stubbedCommands)
            throws Exception {
        Path stubDirectory = Files.createTempDirectory("vega-stub-bin");
        for (var entry : stubbedCommands.entrySet()) {
            Path stub = stubDirectory.resolve(entry.getKey());
            Files.writeString(stub, "#!/bin/sh\necho " + entry.getValue() + "\n", StandardCharsets.UTF_8);
            stub.toFile().setExecutable(true);
        }

        ProcessBuilder builder =
                new ProcessBuilder(List.of("bash", BUILD_SCRIPT.toString(), "--print-target"));
        builder.environment()
                .put("PATH", stubDirectory + ":" + System.getenv("PATH"));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "build script did not report its target");
        assertEquals(0, process.exitValue(), "build script failed: " + output);

        return output
                .lines()
                .filter(line -> line.contains("="))
                .collect(
                        java.util.stream.Collectors.toMap(
                                line -> line.substring(0, line.indexOf('=')),
                                line -> line.substring(line.indexOf('=') + 1)));
    }
}
