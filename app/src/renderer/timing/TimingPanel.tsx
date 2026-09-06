import type { TimingCollector } from './collector';

/**
 * Live timing panel.
 *
 * Ships in the production build rather than behind a developer flag (SC-003). A performance claim
 * nobody can check outside CI is a claim the user has to take on trust; this makes the budgets
 * judgeable on the machine and the file that actually matter.
 *
 * Figures are read on render rather than pushed: the panel repaints far less often than keystrokes
 * arrive, so the sorting cost belongs here and not on the input path.
 */
export function TimingPanel({
  collector,
  onClose,
}: {
  collector: TimingCollector;
  onClose: () => void;
}) {
  const format = (value: number | null) => (value === null ? '—' : `${value.toFixed(1)} ms`);
  const counts = collector.countByKind();
  const interactions = Object.entries(counts)
    .map(([kind, count]) => `${count} ${kind}`)
    .join(', ');

  return (
    <aside className="timing-panel" data-testid="timing-panel" aria-label="Timing">
      <header className="timing-panel__head">
        <strong>Timing</strong>
        <button type="button" onClick={onClose} data-testid="timing-panel-close" aria-label="Close timing panel">
          x
        </button>
      </header>

      <dl className="timing-panel__figures">
        <dt>Input to render p50</dt>
        <dd data-testid="input-render-p50">{format(collector.inputToRenderP50())}</dd>

        <dt>Input to render p95</dt>
        <dd data-testid="input-render-p95">{format(collector.inputToRenderP95())}</dd>

        <dt>Backend round trip p50</dt>
        <dd data-testid="round-trip-p50">{format(collector.roundTripP50())}</dd>

        <dt>Backend round trip p95</dt>
        <dd data-testid="round-trip-p95">{format(collector.roundTripP95())}</dd>
      </dl>

      {/*
        Saying what the figures are over matters: a round trip of "—" means nothing reached the
        backend, which is a different statement from the backend being instant.
      */}
      <p className="timing-panel__scope" data-testid="timing-panel-scope">
        {collector.samples().length === 0
          ? 'No interactions recorded yet'
          : `Over ${collector.samples().length} recent interactions (${interactions})`}
      </p>
    </aside>
  );
}
