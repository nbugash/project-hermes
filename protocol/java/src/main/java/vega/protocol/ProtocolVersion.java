package vega.protocol;

/**
 * Version of the {@code vega/*} custom extension surface.
 *
 * <p>Exchanged during {@code initialize}. A mismatch must fail startup with a clear message rather
 * than degrade, because the alternative is discovering the incompatibility later as a malformed
 * response with no obvious cause.
 */
public final class ProtocolVersion {

    /** Kept in step with protocol/schema/version.json; ProtocolVersionTest enforces the match. */
    public static final String CURRENT = "1.0.0";

    private ProtocolVersion() {}
}
