package vega.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SC-006 through the LSP surface, against the real tree-sitter adapter.
 *
 * <p>Lives in the composition root because it needs both the protocol layer and the real parser, and
 * neither adapter depends on the other — assembling them is precisely this module's job. A version
 * of this test that substituted the parser would assert nothing about error recovery, which is the
 * only thing under test.
 */
class BrokenSyntaxIntegrationTest {

    @TempDir Path tempDir;

    private VegaComponent component;

    private static final String SOURCE =
            """
            class Sample {
                int first() {
                    String kept = "value";
                    return kept.length();
                }

                int second() {
                    int local = 2;
                    return local;
                }
            }
            """;

    @AfterEach
    void tearDown() {
        if (component != null) {
            component.cpuWorkerPool().close();
        }
    }

    private static DidChangeTextDocumentParams change(String uri, int version, Range range, String text) {
        TextDocumentContentChangeEvent event = new TextDocumentContentChangeEvent();
        event.setRange(range);
        event.setText(text);
        return new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, version), List.of(event));
    }

    private static Range at(int line, int character) {
        return new Range(new Position(line, character), new Position(line, character));
    }

    @Test
    void anUnclosedBraceStillYieldsTokensAndRecoversWhenClosed() throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
        String uri = file.toUri().toString();

        component = VegaComponent.create();
        var server = component.languageServer();
        var documents = server.getTextDocumentService();

        documents.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, SOURCE)));

        SemanticTokens healthy =
                documents.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri))).get();
        assertFalse(healthy.getData().isEmpty(), "valid Java should produce tokens");

        // Insert an unclosed brace at the start of the second method's body.
        documents.didChange(change(uri, 2, at(7, 8), "{"));

        SemanticTokens broken =
                documents.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri))).get();

        // Degraded, never blank: the file is invalid but the editor must still show styled code, and
        // the server must still answer rather than erroring the request.
        assertFalse(broken.getData().isEmpty(), "broken Java produced no tokens at all");
        assertTrue(
                broken.getData().size() > healthy.getData().size() / 2,
                "error recovery lost more than half the tokens: "
                        + broken.getData().size()
                        + " of "
                        + healthy.getData().size());

        // Remove the brace again.
        documents.didChange(
                change(uri, 3, new Range(new Position(7, 8), new Position(7, 9)), ""));

        SemanticTokens repaired =
                documents.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri))).get();

        assertEquals(
                healthy.getData(),
                repaired.getData(),
                "closing the brace must restore exactly the original highlighting");
    }

    @Test
    void thePreviousTokensRemainAvailableWhileTheDocumentIsInvalid() throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
        String uri = file.toUri().toString();

        component = VegaComponent.create();
        var documents = component.languageServer().getTextDocumentService();
        documents.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, SOURCE)));
        documents.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri))).get();

        documents.didChange(change(uri, 2, at(7, 8), "{"));

        // A request issued while the document is invalid must still complete. Failing it would leave
        // the editor with no way to refresh until the developer happened to fix the syntax.
        SemanticTokens whileBroken =
                documents.semanticTokensFull(new SemanticTokensParams(new TextDocumentIdentifier(uri))).get();

        assertFalse(whileBroken.getData().isEmpty());
    }
}
