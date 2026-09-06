export interface SaveResponse {
  status: string;
  version?: number;
  contentHash?: string;
  reason?: string;
}

export interface SaveOutcome {
  failed: boolean;
  message: string;
}

/**
 * Turns a save result into something worth showing the user.
 *
 * Each failure leaves unsaved work in the buffer, and the right next step differs: a disk change
 * needs reconciling with someone else's edit, a mirror mismatch is a synchronisation bug worth
 * reporting, and a plain failure is usually the filesystem saying no. A single "save failed" would
 * collapse all three and invite the user to retry into the same wall.
 */
export function describeSaveOutcome(response: SaveResponse): SaveOutcome {
  switch (response.status) {
    case 'written':
      return { failed: false, message: 'Saved' };
    case 'disk-changed':
      return {
        failed: true,
        message: 'Not saved: the file changed on disk since it was opened. Your buffer is unchanged.',
      };
    case 'mirror-mismatch':
      return {
        failed: true,
        message:
          'Not saved: the editor and backend are out of sync, so nothing was written. Your buffer is unchanged.',
      };
    case 'failed':
      return {
        failed: true,
        message: response.reason
          ? `Not saved: ${response.reason}`
          : 'Not saved: the file could not be written.',
      };
    default:
      // An unknown status from a newer backend is treated as failure. Assuming success would tell
      // the user their work is on disk when it may not be — the one wrong answer here.
      return { failed: true, message: `Not saved: unrecognised result "${response.status}".` };
  }
}

/**
 * SHA-256 of the text's UTF-8 bytes, hex-encoded.
 *
 * Matches the backend's `MirrorVerification.hash` exactly, including hashing UTF-8 rather than the
 * UTF-16 both runtimes hold internally. Hashing the internal representation would make the two
 * disagree on every file containing a non-ASCII character, and every save would be refused.
 */
export async function hashText(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}
