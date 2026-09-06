package vega.core.document;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable document text that can be edited without copying the whole document.
 *
 * <p>{@code substring + concat} rebuilds the entire text per keystroke — 1.8 MB on the measured
 * corpus, every character typed. This keeps the text as a list of pieces pointing into existing
 * strings, so an edit allocates a few small records and shares everything either side of it.
 *
 * <p>Immutability is preserved deliberately and is load-bearing: an in-flight highlight finishes
 * against the version it started on precisely because that version cannot change underneath it. Each
 * edit returns a new instance sharing the untouched pieces.
 *
 * <p>Pieces are compacted back to one once there are enough of them. Without that, a long editing
 * session accumulates a piece per keystroke and every character lookup walks all of them — the
 * editor would get steadily slower for no visible reason, which is worse than the copying this
 * replaces because it is invisible until it is severe.
 */
public final class PieceText implements CharSequence {

    /**
     * Pieces tolerated before the text is flattened.
     *
     * <p>A bound on lookup cost, not a correctness parameter. Compaction is O(document), so a higher
     * value makes it rarer and lookups slower; 64 amortises the copy across 64 edits while keeping a
     * lookup to a short walk.
     */
    private static final int MAX_PIECES = 64;

    private record Piece(String source, int start, int length) {}

    private final List<Piece> pieces;
    private final int length;
    private String materialised;

    private PieceText(List<Piece> pieces, int length, String materialised) {
        this.pieces = pieces;
        this.length = length;
        this.materialised = materialised;
    }

    public static PieceText of(String text) {
        return new PieceText(List.of(new Piece(text, 0, text.length())), text.length(), text);
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("index " + index + " of " + length);
        }
        int remaining = index;
        for (Piece piece : pieces) {
            if (remaining < piece.length()) {
                return piece.source().charAt(piece.start() + remaining);
            }
            remaining -= piece.length();
        }
        throw new IllegalStateException("piece lengths do not sum to " + length);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return toString().substring(start, end);
    }

    /** Replaces {@code [start, end)}, sharing every piece the edit does not touch. */
    public PieceText replace(int start, int end, String replacement) {
        List<Piece> updated = new ArrayList<>(pieces.size() + 2);
        int offset = 0;

        for (Piece piece : pieces) {
            int pieceStart = offset;
            int pieceEnd = offset + piece.length();

            // The part of this piece before the edit.
            if (pieceStart < start) {
                int keep = Math.min(piece.length(), start - pieceStart);
                updated.add(new Piece(piece.source(), piece.start(), keep));
            }

            // The part after the edit; its source offset moves by however much was cut from its head.
            if (pieceEnd > end) {
                int skip = Math.max(0, end - pieceStart);
                updated.add(
                        new Piece(piece.source(), piece.start() + skip, piece.length() - skip));
            }

            offset = pieceEnd;

            // The replacement is inserted once, at the piece that contains the edit's start.
            if (pieceStart <= start && start < pieceEnd && !replacement.isEmpty()) {
                // Before the tail fragment if one was just added, otherwise at the end.
                updated.add(
                        updated.size() - (pieceEnd > end ? 1 : 0),
                        new Piece(replacement, 0, replacement.length()));
            }
        }

        // An edit at the very end sits past every piece, so it is appended rather than inserted.
        if (start >= offset && !replacement.isEmpty()) {
            updated.add(new Piece(replacement, 0, replacement.length()));
        }

        updated.removeIf(piece -> piece.length() == 0);
        int newLength = length - (Math.min(end, length) - Math.min(start, length)) + replacement.length();

        if (updated.size() > MAX_PIECES) {
            String flattened = concatenate(updated, newLength);
            return of(flattened);
        }
        return new PieceText(List.copyOf(updated), newLength, null);
    }

    /** How many pieces the text currently holds. Exposed so the compaction bound can be tested. */
    public int pieceCount() {
        return pieces.size();
    }

    @Override
    public String toString() {
        // Cached: save, hashing and full-token requests each ask for the whole text, and rebuilding
        // it per call would reintroduce the copy this class exists to remove.
        if (materialised == null) {
            materialised = concatenate(pieces, length);
        }
        return materialised;
    }

    private static String concatenate(List<Piece> pieces, int length) {
        StringBuilder out = new StringBuilder(length);
        for (Piece piece : pieces) {
            out.append(piece.source(), piece.start(), piece.start() + piece.length());
        }
        return out.toString();
    }
}
