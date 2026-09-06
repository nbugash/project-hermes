package vega.core.document;

/**
 * A single change to a document: an insertion, deletion, paste, or one undo/redo step.
 *
 * <p>Edits carry the version they apply to so that out-of-order application is detectable rather
 * than silently corrupting the backend's mirror of the editor's buffer.
 */
public record Edit(int start, int end, String newText, int baseVersion) {

    public Edit {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid range: " + start + ".." + end);
        }
        if (newText == null) {
            throw new IllegalArgumentException("newText must not be null; use \"\" for a deletion");
        }
        if (baseVersion < 1) {
            throw new IllegalArgumentException("baseVersion must be >= 1, was " + baseVersion);
        }
    }

    public static Edit replace(int start, int end, String newText, int baseVersion) {
        return new Edit(start, end, newText, baseVersion);
    }

    public int resultVersion() {
        return baseVersion + 1;
    }
}
