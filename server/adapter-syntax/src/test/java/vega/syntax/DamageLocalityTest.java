package vega.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vega.core.document.Edit;
import vega.core.highlight.ChangedRange;
import vega.core.highlight.Highlighter;
import vega.core.highlight.Token;
import vega.core.port.CancellationToken;
import vega.core.port.ParsedTree;
import vega.core.port.SyntaxCursor;

/**
 * SC-006: an unclosed brace must degrade highlighting locally, not across the file.
 *
 * <p>This guards a behaviour nobody promises. tree-sitter's error recovery is undocumented and has
 * changed across minor versions before (research D12), so the guarantee the product depends on is
 * pinned here as an executable assertion. Without it a routine dependency bump could turn a local
 * smudge into a whole-file colour collapse, and nothing would fail until a user noticed.
 *
 * <p>Lives in the adapter rather than in {@code server/core}, where tasks.md placed it: the
 * behaviour under test belongs to the parser, and the core deliberately has no parser on its
 * classpath (Constitution Principle III). Placing it in core would have required breaking exactly the
 * boundary the architecture tests enforce.
 */
class DamageLocalityTest {

    private static TreeSitterSyntaxAdapter adapter;

    private static final String SOURCE =
            """
            class Before {
                int untouchedBefore() {
                    String kept = "before";
                    return kept.length();
                }
            }

            class Target {
                int inside() {
                    int local = 1;
                    return local;
                }
            }

            class After {
                int untouchedAfter() {
                    String alsoKept = "after";
                    return alsoKept.length();
                }
            }
            """;

    @BeforeAll
    static void setUp() {
        adapter = new TreeSitterSyntaxAdapter();
    }

    @AfterAll
    static void tearDown() {
        adapter.close();
    }

    private static List<Token> tokensOf(ParsedTree tree, String text) {
        try (SyntaxCursor cursor = tree.cursor()) {
            return new Highlighter().highlight(cursor, new ChangedRange(0, text.length()));
        }
    }

    /** Tokens strictly before an offset, compared by position and type. */
    private static List<Token> before(List<Token> tokens, int offset) {
        return tokens.stream().filter(token -> token.end() <= offset).toList();
    }

    @Test
    void anUnclosedBraceLeavesTokensBeforeItUnchanged() {
        int braceOffset = SOURCE.indexOf("int local = 1;");
        String broken = SOURCE.substring(0, braceOffset) + "{" + SOURCE.substring(braceOffset);

        try (ParsedTree original = adapter.parse(SOURCE, CancellationToken.never())) {
            // Captured before the reparse: reparse consumes the tree it is given, shifting its node
            // positions in place. Reading `original` afterwards yields offsets moved by the edit.
            List<Token> healthy = before(tokensOf(original, SOURCE), braceOffset);

            try (ParsedTree damaged =
                    adapter.reparse(
                            original,
                            Edit.replace(braceOffset, braceOffset, "{", 1),
                            broken,
                            CancellationToken.never())) {
                List<Token> afterDamage = before(tokensOf(damaged, broken), braceOffset);

                assertEquals(
                        healthy.size(),
                        afterDamage.size(),
                        "an unclosed brace changed the token count before the damage");
                for (int i = 0; i < healthy.size(); i++) {
                    assertEquals(
                            healthy.get(i), afterDamage.get(i), "token " + i + " before the damage changed");
                }
            }
        }
    }

    @Test
    void theErrorIsReportedAndConfinedRatherThanSwallowed() {
        int braceOffset = SOURCE.indexOf("int local = 1;");
        String broken = SOURCE.substring(0, braceOffset) + "{" + SOURCE.substring(braceOffset);

        try (ParsedTree original = adapter.parse(SOURCE, CancellationToken.never());
                ParsedTree damaged =
                        adapter.reparse(
                                original,
                                Edit.replace(braceOffset, braceOffset, "{", 1),
                                broken,
                                CancellationToken.never())) {

            assertFalse(original.hasError(), "the starting source is valid Java");
            assertTrue(damaged.hasError(), "an unclosed brace must be reported as an error");

            // Tokens still exist across the whole document: recovery degrades colour, it does not
            // blank the file. A parser that gave up would leave nothing to paint at all.
            List<Token> tokens = tokensOf(damaged, broken);
            assertTrue(tokens.size() > 10, "error recovery produced almost no tokens: " + tokens.size());
        }
    }

    @Test
    void closingTheBraceRestoresTheOriginalHighlighting() {
        int braceOffset = SOURCE.indexOf("int local = 1;");
        String broken = SOURCE.substring(0, braceOffset) + "{" + SOURCE.substring(braceOffset);

        List<Token> healthy;
        try (ParsedTree original = adapter.parse(SOURCE, CancellationToken.never())) {
            healthy = tokensOf(original, SOURCE);

            try (ParsedTree damaged =
                            adapter.reparse(
                                    original,
                                    Edit.replace(braceOffset, braceOffset, "{", 1),
                                    broken,
                                    CancellationToken.never());
                    ParsedTree repaired =
                            adapter.reparse(
                                    damaged,
                                    Edit.replace(braceOffset, braceOffset + 1, "", 2),
                                    SOURCE,
                                    CancellationToken.never())) {

                assertFalse(repaired.hasError(), "removing the brace must clear the error");

                // Recovery has to be complete, not approximate: a document that never returns to its
                // original highlighting accumulates damage over an editing session.
                assertEquals(healthy, tokensOf(repaired, SOURCE));
            }
        }
    }

    @Test
    void damageDoesNotExtendToTheFollowingTopLevelClass() {
        int braceOffset = SOURCE.indexOf("int local = 1;");
        int afterClassOffset = SOURCE.indexOf("class After");
        String broken = SOURCE.substring(0, braceOffset) + "{" + SOURCE.substring(braceOffset);

        List<String> healthyTypesBefore;
        try (ParsedTree original = adapter.parse(SOURCE, CancellationToken.never())) {
            healthyTypesBefore =
                    tokensOf(original, SOURCE).stream()
                            .filter(token -> token.start() >= afterClassOffset)
                            .map(Token::type)
                            .toList();

            try (ParsedTree damaged =
                    adapter.reparse(
                            original,
                            Edit.replace(braceOffset, braceOffset, "{", 1),
                            broken,
                            CancellationToken.never())) {

            // Everything after the damage shifts by one character, so compare token *types* in
            // order rather than absolute offsets: the question is whether the parser still
            // understands the later class, not where it sits.
            List<String> damagedTypes =
                    tokensOf(damaged, broken).stream()
                            .filter(token -> token.start() >= afterClassOffset + 1)
                            .map(Token::type)
                            .toList();

                assertEquals(healthyTypesBefore, damagedTypes, "damage leaked into the following class");
            }
        }
    }
}
