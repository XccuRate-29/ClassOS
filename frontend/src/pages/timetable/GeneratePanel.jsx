import { useState } from 'react';
import { api } from '../../api/client.js';

export function GeneratePanel({ term, onGenerated }) {
  const [confirming, setConfirming] = useState(false);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');

  async function generate() {
    setConfirming(false);
    setRunning(true);
    setError('');
    setResult(null);
    try {
      const r = await api.post('/admin/timetable/generate', { termId: term.id });
      setResult(r);
      onGenerated();
    } catch (err) {
      setError(err.message);
    } finally {
      setRunning(false);
    }
  }

  return (
    <section className="rounded-md border border-line bg-white p-5" aria-labelledby="generate-heading">
      <h2 id="generate-heading" className="text-lg font-bold">Generate the timetable</h2>
      <p className="text-sm text-muted">
        Schedules every remaining lecture and lab for {term.name}. Classes you added or moved by hand,
        and pinned classes, stay where they are.
      </p>

      {!confirming && (
        <button type="button" disabled={running} onClick={() => setConfirming(true)}
                className="mt-4 rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep disabled:opacity-50">
          {running ? 'Generating…' : 'Generate timetable'}
        </button>
      )}
      {confirming && (
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <span>Replace all generated classes in {term.name}?</span>
          <button type="button" onClick={generate} className="rounded bg-board px-4 py-2 font-bold text-white">
            Generate
          </button>
          <button type="button" onClick={() => setConfirming(false)} className="rounded border border-line px-4 py-2">
            Cancel
          </button>
        </div>
      )}

      <div aria-live="polite">
        {error && <p role="alert" className="mt-4 text-danger">{error}</p>}
        {result && (
          <div className="mt-4">
            <p className={result.status === 'COMPLETE' ? 'text-ok' : 'font-bold'}>
              Placed {result.eventsPlaced} of {result.eventsTotal} classes
              {result.status === 'COMPLETE' ? '.' : '. The rest need your attention:'}
            </p>
            <p className="text-sm text-muted">
              {result.solverMs} ms, {result.nodesExplored} search steps, {result.backtracks} backtracks
              {result.limitReached ? ', stopped at the time limit' : ''}. Kept {result.slotsKept} manual or pinned classes.
            </p>
            {result.unplaced.length > 0 && (
              <ul className="mt-2 space-y-1 text-sm">
                {result.unplaced.map((u, i) => (
                  <li key={i} className="border-l-4 border-danger pl-2">
                    <span className="font-bold">{u.courseCode} for {u.sectionName}</span>{' '}
                    ({u.kind === 'LAB' ? `lab, ${u.lengthPeriods} periods` : 'lecture'}): {u.reason}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
