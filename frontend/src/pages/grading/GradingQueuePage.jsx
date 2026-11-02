import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client.js';
import { DownloadButton } from '../../components/DownloadButton.jsx';
import { PRIORITY_LABEL, dateTime } from '../../lib/format.js';
import { GradeForm } from '../classroom/GradeForm.jsx';

const POLICIES = [
  { value: 'FCFS', label: 'First come, first served',
    help: 'Oldest submission first. Simple and predictable, but one large assignment can make every other class wait.' },
  { value: 'PRIORITY', label: 'Priority with aging',
    help: 'High-priority assignments first. A submission gains priority the longer it waits, so low-priority work is never stuck forever.' },
  { value: 'ROUND_ROBIN', label: 'Round robin across assignments',
    help: 'Takes a few submissions from each assignment in turn, so every class sees grades coming back.' },
];

export default function GradingQueuePage() {
  const [policy, setPolicy] = useState('FCFS');
  const [aging, setAging] = useState('0.25');
  const [quantum, setQuantum] = useState('3');
  const [queue, setQueue] = useState(null);
  const [compare, setCompare] = useState(null);
  const [error, setError] = useState('');
  const [compareError, setCompareError] = useState('');
  const [open, setOpen] = useState(null);

  const params = `agingPerHour=${encodeURIComponent(aging || 0)}&quantum=${encodeURIComponent(quantum || 1)}`;

  const load = useCallback(async () => {
    try {
      setQueue(await api.get(`/teacher/grading/queue?policy=${policy}&${params}`));
      setError('');
    } catch (err) {
      setError(err.message);
    }
    try {
      setCompare(await api.get(`/teacher/grading/compare?${params}`));
      setCompareError('');
    } catch (err) {
      setCompare(null);
      setCompareError(err.message);
    }
  }, [policy, params]);

  useEffect(() => { load(); }, [load]);

  const current = POLICIES.find((p) => p.value === policy);

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold">Grading queue</h1>
        <p className="text-muted">Everything waiting to be graded in your courses, in the order the chosen policy suggests.</p>
      </div>

      <section aria-labelledby="policy-heading" className="rounded-md border border-line bg-white p-5">
        <h2 id="policy-heading" className="text-lg font-bold">Order by</h2>
        <fieldset className="mt-3">
          <legend className="sr-only">Scheduling policy</legend>
          <div className="grid gap-3 sm:grid-cols-3">
            {POLICIES.map((p) => (
              <label key={p.value}
                     className={`cursor-pointer rounded border p-3 ${policy === p.value ? 'border-board bg-paper' : 'border-line'}`}>
                <input type="radio" name="policy" value={p.value} checked={policy === p.value}
                       onChange={() => setPolicy(p.value)} className="mr-2" />
                <span className="font-bold">{p.label}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <p className="mt-3 text-sm">{current.help}</p>
        <div className="mt-3 flex flex-wrap gap-4">
          {policy === 'PRIORITY' && (
            <div>
              <label htmlFor="aging" className="block text-sm font-bold">Aging (priority points per hour waited)</label>
              <input id="aging" type="number" min="0" step="0.05" value={aging} onChange={(e) => setAging(e.target.value)}
                     className="mt-1 w-32 rounded border border-line px-2 py-1.5" />
            </div>
          )}
          {policy === 'ROUND_ROBIN' && (
            <div>
              <label htmlFor="quantum" className="block text-sm font-bold">Submissions per assignment per turn</label>
              <input id="quantum" type="number" min="1" max="100" value={quantum} onChange={(e) => setQuantum(e.target.value)}
                     className="mt-1 w-32 rounded border border-line px-2 py-1.5" />
            </div>
          )}
        </div>
      </section>

      {error && <p role="alert" className="text-danger">{error}</p>}
      {queue?.fallback && <p role="status" className="border-l-4 border-highlight pl-3">{queue.note}</p>}

      {queue && queue.items.length === 0 && <p className="text-muted">Nothing is waiting to be graded.</p>}
      {queue && queue.items.length > 0 && (
        <section aria-labelledby="queue-heading">
          <h2 id="queue-heading" className="text-lg font-bold">{queue.items.length} waiting</h2>
          <ol className="mt-2 space-y-2">
            {queue.items.map((it) => (
              <li key={`${it.submissionId}-${it.version}`} className="rounded-md border border-line bg-white p-4">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <span className="flex items-baseline gap-3">
                    <span className="w-8 text-right text-xl font-bold tabular-nums">{it.position}</span>
                    <span>
                      <span className="font-bold">{it.studentName}</span>, {it.courseCode} {it.assignmentTitle}
                      <span className="block text-sm text-muted">
                        {it.sectionName}, submitted {dateTime(it.submittedAt)}{it.late ? ' (late)' : ''},
                        {' '}{PRIORITY_LABEL[it.priority].toLowerCase()} priority, about {it.estMinutes} min.
                        {' '}Grade by {dateTime(it.gradeBy)}.
                      </span>
                    </span>
                  </span>
                  <button type="button" onClick={() => setOpen(open === it.submissionId ? null : it.submissionId)}
                          aria-expanded={open === it.submissionId}
                          className="rounded bg-board px-4 py-1.5 font-bold text-white hover:bg-board-deep">
                    {open === it.submissionId ? 'Close' : 'Grade'}
                  </button>
                </div>
                {open === it.submissionId && (
                  <div className="mt-4 space-y-3 border-t border-line pt-4">
                    <DownloadButton path={`/workspace/submissions/${it.submissionId}/file`} label="Download submission" />
                    <GradeItem item={it} onDone={() => { setOpen(null); load(); }} />
                  </div>
                )}
              </li>
            ))}
          </ol>
        </section>
      )}

      <section aria-labelledby="compare-heading" className="space-y-2">
        <h2 id="compare-heading" className="text-lg font-bold">How the policies compare on this queue</h2>
        <p className="text-sm text-muted">
          A simulation: grading one submission after another starting now, using each assignment's estimated minutes.
          Lower waits are better; fairness is 1 when every assignment's students wait equally long.
        </p>
        {compareError && <p className="text-sm text-muted">{compareError}</p>}
        {compare && compare.policies.length > 0 && (
          <div className="overflow-x-auto rounded-md border border-line bg-white">
            <table className="w-full min-w-[36rem] text-left text-sm">
              <thead className="border-b border-line text-muted">
                <tr>
                  <th scope="col" className="px-4 py-2 font-bold">Policy</th>
                  <th scope="col" className="px-4 py-2 font-bold">Average wait</th>
                  <th scope="col" className="px-4 py-2 font-bold">Longest wait</th>
                  <th scope="col" className="px-4 py-2 font-bold">Past grade-by date</th>
                  <th scope="col" className="px-4 py-2 font-bold">Fairness</th>
                </tr>
              </thead>
              <tbody>
                {compare.policies.map((m) => (
                  <tr key={m.policy} className={`border-b border-line last:border-0 ${m.policy === policy ? 'bg-paper font-bold' : ''}`}>
                    <td className="px-4 py-2">{POLICIES.find((p) => p.value === m.policy)?.label || m.policy}</td>
                    <td className="px-4 py-2">{hours(m.avgWaitHours)}</td>
                    <td className="px-4 py-2">{hours(m.maxWaitHours)}</td>
                    <td className="px-4 py-2">{m.deadlineMisses}</td>
                    <td className="px-4 py-2">{m.fairness.toFixed(2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function hours(h) {
  return h >= 48 ? `${(h / 24).toFixed(1)} days` : `${h.toFixed(1)} h`;
}

function GradeItem({ item, onDone }) {
  const [maxMarks, setMaxMarks] = useState(null);
  useEffect(() => {
    api.get(`/workspace/assignments/${item.assignmentId}`).then((d) => setMaxMarks(d.assignment.maxMarks)).catch(() => {});
  }, [item.assignmentId]);
  if (maxMarks == null) return <p className="text-sm text-muted">Loading…</p>;
  return (
    <GradeForm submission={{ id: item.submissionId, version: item.version, status: 'SUBMITTED', marks: null, feedback: '' }}
               maxMarks={maxMarks} onGraded={onDone} />
  );
}
