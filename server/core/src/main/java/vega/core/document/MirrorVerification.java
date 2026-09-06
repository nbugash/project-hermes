package vega.core.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Proves the backend's mirror matches the editor's buffer before anything is written.
 *
 * <p>{@code vega/saveDocument} deliberately carries no text: the backend already holds a synchronised
 * copy, and sending 50,000 lines back so it can write bytes it has would be waste. That economy is
 * only safe with a check — the editor sends a hash of what it believes it has, and a mismatch means
 * the two have drifted. Writing then would persist text the user never saw, so the mismatch is
 * reported as an outcome rather than resolved by guessing.
 */
public final class MirrorVerification {

    private MirrorVerification() {}

    /** SHA-256 of the text's UTF-8 bytes, hex-encoded. */
    public static String hash(CharSequence text) {
        return hashBytes(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 of raw bytes, hex-encoded.
     *
     * <p>Hashing bytes rather than Java chars is what lets the editor compute the same value: it
     * holds UTF-16 internally too, but the agreed representation on the wire is UTF-8. Hashing chars
     * would make the two disagree on every file with a non-ASCII character in it.
     */
    public static String hashBytes(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; its absence is not a runtime condition to
            // handle but a broken installation.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Whether the mirror matches the hash the editor supplied.
     *
     * <p>A missing or blank hash is a mismatch, not a pass: a client that sends nothing has proved
     * nothing, and treating absence as agreement would make the check bypassable by omission.
     */
    public static boolean matches(CharSequence mirrorText, String expectedHash) {
        if (expectedHash == null || expectedHash.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(mirrorText).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
