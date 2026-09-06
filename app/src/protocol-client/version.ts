/** Protocol version this client speaks. Kept in step with protocol/schema/version.json. */
export const CLIENT_PROTOCOL_VERSION = '1.0.0';

interface SemVer {
  major: number;
  minor: number;
  patch: number;
}

function parse(version: string): SemVer {
  const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(version);
  if (!match) {
    throw new Error(`Unparseable protocol version: "${version}"`);
  }
  return { major: Number(match[1]), minor: Number(match[2]), patch: Number(match[3]) };
}

/**
 * Fails startup when the backend cannot speak this client's protocol.
 *
 * A differing major is a breaking change by definition. A server *older* than the client within the
 * same major is also rejected: the client may use a method added in a later minor, and discovering
 * that as "method not found" mid-session is strictly worse than refusing to start.
 */
export function assertProtocolCompatible(
  serverVersion: string,
  clientVersion: string = CLIENT_PROTOCOL_VERSION,
): void {
  const server = parse(serverVersion);
  const client = parse(clientVersion);

  if (server.major !== client.major) {
    throw new Error(
      `Incompatible vega protocol: server speaks ${serverVersion}, client speaks ${clientVersion}. ` +
        'Major versions must match.',
    );
  }

  if (server.minor < client.minor) {
    throw new Error(
      `Backend protocol ${serverVersion} is older than the client's ${clientVersion}; ` +
        'the client may use methods this backend does not implement.',
    );
  }
}
