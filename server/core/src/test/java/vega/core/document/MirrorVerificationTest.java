package vega.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The save payload carries no text — the backend writes from its own mirror.
 *
 * <p>That is what makes saving cheap on a 50,000-line file, and it is only safe if the mirror
 * provably matches the editor's buffer. The editor sends a hash of what it believes it has; a
 * mismatch means the two have drifted, and writing then would persist text the user never saw. So
 * drift becomes an explicit refusal rather than silent corruption.
 */
class MirrorVerificationTest {

    private static final String TEXT = "class A {\n    int x;\n}\n";

    @Test
    void hashesTheMirrorTextStably() {
        assertEquals(MirrorVerification.hash(TEXT), MirrorVerification.hash(TEXT));
    }

    @Test
    void differentTextHashesDifferently() {
        assertNotEquals(MirrorVerification.hash(TEXT), MirrorVerification.hash(TEXT + "\n"));
    }

    @Test
    void hashesOverUtf8BytesSoTheEditorCanComputeTheSameValue() {
        // The client hashes bytes, not Java chars. If this hashed UTF-16 the two would disagree on
        // every file containing a non-ASCII character, and saving would be refused for no reason.
        String accented = "café";
        assertEquals(
                MirrorVerification.hash(accented),
                MirrorVerification.hashBytes(accented.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsAMatchingMirror() {
        assertTrue(MirrorVerification.matches(TEXT, MirrorVerification.hash(TEXT)));
    }

    @Test
    void rejectsADriftedMirror() {
        assertFalse(MirrorVerification.matches(TEXT, MirrorVerification.hash("something else")));
    }

    @Test
    void rejectsAMissingHashRatherThanAssumingAgreement() {
        // A client that sends no hash has not proved anything. Treating absence as agreement would
        // make the check trivially bypassable and defeat its purpose.
        assertFalse(MirrorVerification.matches(TEXT, null));
        assertFalse(MirrorVerification.matches(TEXT, ""));
    }

    @Test
    void isNotConfusedByTrailingWhitespaceDifferences() {
        assertFalse(MirrorVerification.matches(TEXT, MirrorVerification.hash(TEXT + " ")));
    }
}
