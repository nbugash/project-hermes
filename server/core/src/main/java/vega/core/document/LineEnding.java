package vega.core.document;

/**
 * Line-ending style observed when the file was read.
 *
 * <p>{@link #MIXED} is a real case, not a defect: a file with inconsistent endings must be written
 * back preserving each line's original ending rather than being normalised, or an unmodified save
 * would not be byte-identical.
 */
public enum LineEnding {
    LF,
    CRLF,
    MIXED
}
