package vega.syntax;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import org.junit.jupiter.api.Test;

/**
 * Proves the bundled native libraries actually load and export the symbols we depend on.
 *
 * <p>Worth a test rather than trusting the build: jtreesitter ships no natives, so these are built
 * from pinned sources by our own script. A missing or mis-built library fails here, at a named
 * assertion, instead of surfacing later as an unsatisfied link error inside a parse.
 */
class BundledNativeLibraryLookupTest {

    @Test
    void resolvesCoreTreeSitterSymbols() {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = new BundledNativeLibraryLookup().get(arena);

            assertTrue(lookup.find("ts_parser_new").isPresent(), "ts_parser_new must be exported");
            assertTrue(lookup.find("ts_parser_parse").isPresent(), "ts_parser_parse must be exported");
            assertTrue(
                    lookup.find("ts_tree_get_changed_ranges").isPresent(),
                    "ts_tree_get_changed_ranges must be exported; the narrowing strategy depends on it");
        }
    }

    @Test
    void resolvesTheJavaGrammarEntryPoint() {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = new BundledNativeLibraryLookup().get(arena);

            assertTrue(
                    lookup.find("tree_sitter_java").isPresent(),
                    "the Java grammar entry point must be exported");
        }
    }
}
