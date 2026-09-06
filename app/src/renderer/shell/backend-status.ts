export type BackendStatus = 'starting' | 'indexing' | 'ready' | 'failed';

export interface BackendStatusView {
  /** Human-readable status for the shell's status area. */
  label: string;
  /** Whether to indicate that analysis features are incomplete. */
  degraded: boolean;
  /** Whether the document may still be edited. Always true — see below. */
  editable: boolean;
}

/**
 * Maps backend status to what the shell should show.
 *
 * Editing stays enabled in every state, including failure. The backend owns analysis, not the
 * buffer: disabling the editor when highlighting dies would convert a degraded session into lost
 * work, which is a strictly worse outcome than uncoloured text.
 */
export function describeBackendStatus(status: BackendStatus, detail?: string): BackendStatusView {
  switch (status) {
    case 'starting':
      return { label: 'Backend starting — highlighting will appear shortly', degraded: true, editable: true };
    case 'indexing':
      return { label: 'Analysing document…', degraded: true, editable: true };
    case 'ready':
      return { label: 'Ready', degraded: false, editable: true };
    case 'failed':
      return {
        label: detail ? `Backend unavailable: ${detail}` : 'Backend unavailable',
        degraded: true,
        editable: true,
      };
  }
}
