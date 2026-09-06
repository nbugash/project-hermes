import type { VegaBridge } from '../../preload/bridge';

export interface LoadedDocument {
  uri: string;
  text: string;
  metadata: {
    encoding: string;
    lineEnding: 'LF' | 'CRLF' | 'MIXED';
    hasTrailingNewline: boolean;
    contentHash: string;
  };
}

/**
 * Loads a document's contents through the backend bridge.
 *
 * The renderer never touches the filesystem (FR-002, FR-021) — it has no Node access to do so with,
 * and routing reads through the backend is what lets that backend move to another machine later
 * without changing this call.
 */
export async function loadDocument(bridge: VegaBridge, uri: string): Promise<LoadedDocument> {
  try {
    const content = await bridge.readDocument(uri);
    return {
      uri,
      text: content.text,
      metadata: {
        encoding: content.encoding,
        lineEnding: content.lineEnding,
        hasTrailingNewline: content.hasTrailingNewline,
        contentHash: content.contentHash,
      },
    };
  } catch (cause) {
    // The uri is the one piece of context that makes this actionable; without it the message is
    // indistinguishable across every document the user has open.
    throw new Error(`Failed to read ${uri}: ${cause instanceof Error ? cause.message : String(cause)}`, {
      cause,
    });
  }
}
