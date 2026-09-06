import type { LoadedDocument } from '../editor/load-document';
import type { TimingCollector } from '../timing/collector';

/**
 * Facts about the open document, and the editor's own latency.
 *
 * The latency figure is the one loud element in the interface, and deliberately so: an editor that
 * continuously reports whether it is meeting its input-to-render budget is this product's whole
 * argument. Burying that in a panel nobody opens would be the wrong emphasis.
 */
export function StatusBar({
  document,
  lineCount,
  collector,
  budgetMs = 16,
}: {
  document: LoadedDocument | null;
  lineCount: number;
  collector: TimingCollector;
  budgetMs?: number;
}) {
  const p95 = collector.inputToRenderP95();
  const recent = collector
    .samples()
    .slice(-24)
    .map((sample) => sample.inputToRenderMs);

  return (
    <footer className="status-bar">
      {document === null ? (
        <span>No document open</span>
      ) : (
        <>
          <span>{lineCount.toLocaleString()} lines</span>
          <span>{document.metadata.encoding}</span>
          <span>{document.metadata.lineEnding}</span>
        </>
      )}

      <span className="status-bar__spacer" />

      {p95 === null ? (
        // Nothing measured yet says exactly that, rather than showing a zero that looks like a
        // result.
        <span>Awaiting input</span>
      ) : (
        <span className="status-bar__latency">
          <Sparkline values={recent} budgetMs={budgetMs} />
          <span
            className="status-bar__figure"
            data-state={p95 <= budgetMs ? 'within' : 'over'}
            data-testid="status-latency"
          >
            {p95.toFixed(1)}
          </span>
          <span className="status-bar__unit">ms p95 keystroke to frame</span>
        </span>
      )}
    </footer>
  );
}

/**
 * Recent keystroke latencies, most recent at the right.
 *
 * Scaled against the budget rather than against its own maximum: a sparkline normalised to its own
 * data always looks the same, which would hide exactly the thing worth seeing. The budget line is
 * the reference, so bars crossing it are visibly over.
 */
function Sparkline({ values, budgetMs }: { values: readonly number[]; budgetMs: number }) {
  if (values.length < 2) {
    return null;
  }

  const width = 52;
  const height = 14;
  const ceiling = Math.max(budgetMs * 1.5, ...values);
  const step = width / (values.length - 1);
  const points = values
    .map((value, index) => `${index * step},${height - (value / ceiling) * height}`)
    .join(' ');
  const budgetY = height - (budgetMs / ceiling) * height;

  return (
    <svg
      className="status-bar__spark"
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      aria-hidden="true"
    >
      <line x1="0" y1={budgetY} x2={width} y2={budgetY} stroke="#5c6b7d" strokeWidth="1" strokeDasharray="2 2" />
      <polyline points={points} fill="none" stroke="#8494a6" strokeWidth="1.25" strokeLinejoin="round" />
    </svg>
  );
}
