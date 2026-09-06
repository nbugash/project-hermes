package vega.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Version discipline is what lets every later stage tell "stale" from "wrong".
 *
 * <p>Highlighting, token deltas and saving all key off the version. If a version is ever reused or
 * skipped, a stale result becomes indistinguishable from a current one and gets painted, so the
 * failure surfaces as wrong colours rather than as a version error.
 */
class VersionMonotonicityTest {

    private static Document open(String text) {
        return Document.opened("file:///A.java", text);
    }

    @Test
    void everyEditIncrementsTheVersionByExactlyOne() {
        Document document = open("class A {}");
        int start = document.version();

        Document once = document.apply(Edit.replace(0, 0, "a", start));
        Document twice = once.apply(Edit.replace(0, 0, "b", once.version()));

        assertEquals(start + 1, once.version());
        assertEquals(start + 2, twice.version());
    }

    @Test
    void versionsAreNeverReusedAcrossASequenceOfEdits() {
        Document document = open("class A {}");
        Set<Integer> seen = new HashSet<>();
        seen.add(document.version());

        for (int i = 0; i < 50; i++) {
            document = document.apply(Edit.replace(0, 0, "x", document.version()));
            assertTrue(seen.add(document.version()), "version " + document.version() + " was reused");
        }
    }

    @Test
    void anEditAgainstAnEarlierVersionIsRejected() {
        Document document = open("class A {}");
        Edit stale = Edit.replace(0, 0, "a", document.version());
        Document moved = document.apply(stale);

        // Applying the same edit twice is the shape a duplicated or replayed notification takes.
        assertThrows(IllegalStateException.class, () -> moved.apply(stale));
    }

    @Test
    void anEditAgainstAFutureVersionIsRejected() {
        Document document = open("class A {}");

        // Arriving out of order is not recoverable by guessing: the text this edit was computed
        // against does not exist here, so applying it would corrupt the mirror silently.
        assertThrows(
                IllegalStateException.class,
                () -> document.apply(Edit.replace(0, 0, "a", document.version() + 5)));
    }

    @Test
    void theOriginalDocumentIsUnchangedByAnEdit() {
        Document document = open("class A {}");

        document.apply(Edit.replace(0, 0, "a", document.version()));

        // Immutability is what makes an in-flight highlight safe to finish against the version it
        // started on rather than racing the next keystroke.
        assertEquals("class A {}", document.text());
    }
}
