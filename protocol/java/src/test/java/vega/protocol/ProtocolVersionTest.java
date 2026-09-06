package vega.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The protocol version is declared once, in the schema, and both runtimes read it from there.
 *
 * <p>This test exists because a hard-coded constant that silently drifts from the schema is exactly
 * how a version handshake stops being a handshake: both sides keep agreeing on a number that no
 * longer describes the wire format.
 */
class ProtocolVersionTest {

    @Test
    void constantMatchesTheSchemaResource() throws Exception {
        try (InputStream in =
                ProtocolVersion.class.getResourceAsStream("/vega-protocol-version.json")) {
            assertNotNull(in, "vega-protocol-version.json must ship on the classpath");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            String fromSchema = json.replaceAll("(?s).*\"protocolVersion\"\\s*:\\s*\"([^\"]+)\".*", "$1");

            assertEquals(fromSchema, ProtocolVersion.CURRENT);
        }
    }
}
