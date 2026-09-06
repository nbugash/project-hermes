package vega.syntax;

import io.github.treesitter.jtreesitter.NativeLibraryLookup;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Loads the tree-sitter native libraries bundled in this module's resources.
 *
 * <p>jtreesitter ships no natives and no grammars, so we build both from pinned sources with
 * {@code -O3 -DNDEBUG} and carry them here. Extracting from the jar through this SPI keeps the
 * deployment a single artifact — the alternatives (OS library search path, {@code
 * java.library.path}) push installation steps onto whoever runs the backend.
 *
 * <p>Note the load order: the grammar library references symbols from the core library, so the core
 * must be resolved first.
 */
public final class BundledNativeLibraryLookup implements NativeLibraryLookup {

    /**
     * Library file names for the running platform.
     *
     * <p>{@link System#mapLibraryName} rather than a hardcoded {@code .so}: macOS uses {@code .dylib}
     * and Windows {@code .dll}, and getting it wrong fails at run time with a missing-symbol error
     * that says nothing about the extension. The build script picks the matching name for the
     * platform it runs on.
     */
    private static final List<String> LIBRARIES =
            List.of(System.mapLibraryName("tree-sitter"), System.mapLibraryName("tree-sitter-java"));

    /**
     * The file names this loader expects, in load order.
     *
     * <p>Exposed so a test can assert the build script produces exactly these. The mismatch that
     * makes this worth checking — a {@code .so} bundled where a {@code .dylib} is wanted — fails at
     * run time with a missing-symbol error that never mentions the extension.
     */
    static List<String> libraryNames() {
        return LIBRARIES;
    }

    @Override
    public SymbolLookup get(Arena arena) {
        Path directory = extractAll();

        SymbolLookup combined = null;
        for (String library : LIBRARIES) {
            SymbolLookup next = SymbolLookup.libraryLookup(directory.resolve(library), arena);
            combined = combined == null ? next : combined.or(next);
        }
        return combined;
    }

    private static Path extractAll() {
        try {
            Path directory = Files.createTempDirectory("vega-native");
            directory.toFile().deleteOnExit();

            for (String library : LIBRARIES) {
                String resource = "/native/" + library;
                try (InputStream in = BundledNativeLibraryLookup.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IllegalStateException(
                                "Missing bundled native library "
                                        + resource
                                        + ". Run server/adapter-syntax/native/build-native.sh");
                    }
                    Path target = directory.resolve(library);
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().deleteOnExit();
                }
            }
            return directory;
        } catch (IOException e) {
            throw new IllegalStateException("Could not extract bundled native libraries", e);
        }
    }
}
