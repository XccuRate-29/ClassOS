import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client.js';
import { hhmm } from '../../lib/time.js';
import { LiveSession, StudentList } from './LiveSession.jsx';

export default function TeacherAttendancePage() {
  const [today, setToday] = useState(null);
  const [history, setHistory] = useState([]);
  const [error, setError] = useState('');
  const [adhoc, setAdhoc] = useState('');
  const [lastClosed, setLastClosed] = useState(null);

  const load = useCallback(async () => {
    try {
      const [t, h] = await Promise.all([api.get('/teacher/attendance/today'), api.get('/teacher/attendance/history')]);
      setToday(t);
      setHistory(h);
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  async function start(offeringId, slotId) {
    setError('');
    setLastClosed(null);
    try {
      await api.post('/teacher/attendance/sessions', { offeringId, slotId });
      await load();
    } catch (err) {
      setError(err.message);
    }
  }

  const onClosed = useCallback(async () => {
    const openId = today?.openSessionId;
    if (openId) {
      try { setLastClosed(await api.get(`/teacher/attendance/sessions/${openId}`)); } catch { /* shown on reload */ }
    }
    await load();
  }, [today?.openSessionId, load]);

  if (!today) return error ? <p role="alert" className="text-danger">{error}</p> : <p className="text-muted" role="status">Loading…</p>;

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold">Attendance</h1>
        <p className="text-muted">
          Students scan the code with their phone camera or type it on the Attendance page. It changes every few seconds,
          so a photo sent to a friend stops working almost at once.
        </p>
      </div>

      {error && <p role="alert" className="text-danger">{error}</p>}

      {today.openSessionId ? (
        <LiveSession sessionId={today.openSessionId} onClosed={onClosed} />
      ) : (
        <>
          {lastClosed && (
            <section className="space-y-3" aria-labelledby="closed-heading">
              <h2 id="closed-heading" className="text-lg font-bold">
                {lastClosed.courseCode} for {lastClosed.sectionName}: {lastClosed.presentCount} of {lastClosed.enrolledCount} present
              </h2>
              <p className="text-sm text-muted">You can still correct individual records.</p>
              <StudentList detail={lastClosed}
                           onSet={async (studentId, present) => setLastClosed(await api.post(
                             `/teacher/attendance/sessions/${lastClosed.id}/manual`, { studentId, present }))} />
            </section>
          )}

          <section aria-labelledby="today-heading">
            <h2 id="today-heading" className="text-lg font-bold">Your classes today</h2>
            {today.classes.length === 0 ? (
              <p className="mt-2 text-muted">No timetabled classes today. You can still take attendance below.</p>
            ) : (
              <ul className="mt-2 divide-y divide-line border-y border-line">
                {today.classes.map((c) => (
                  <li key={c.slotId} className="flex flex-wrap items-center justify-between gap-3 py-3">
                    <span>
                      <span className="font-bold">{hhmm(c.startTime)}–{hhmm(c.endTime)}</span>{' '}
                      {c.courseCode} {c.courseTitle}, {c.sectionName}, {c.roomCode}
                    </span>
                    <button type="button" onClick={() => start(c.offeringId, c.slotId)}
                            className="rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep">
                      Start attendance
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section aria-labelledby="adhoc-heading" className="rounded-md border border-line bg-white p-5">
            <h2 id="adhoc-heading" className="text-lg font-bold">Extra or rescheduled class</h2>
            <div className="mt-3 flex flex-wrap items-end gap-3">
              <div>
                <label htmlFor="adhoc" className="font-bold">Course and section</label>
                <select id="adhoc" value={adhoc} onChange={(e) => setAdhoc(e.target.value)}
                        className="mt-1 block min-w-64 rounded border border-line bg-white px-3 py-2">
                  <option value="">Choose…</option>
                  {today.offerings.map((o) => (
                    <option key={o.offeringId} value={o.offeringId}>{o.courseCode} {o.courseTitle}, {o.sectionName}</option>
                  ))}
                </select>
              </div>
              <button type="button" disabled={!adhoc} onClick={() => start(Number(adhoc), null)}
                      className="rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep disabled:opacity-50">
                Start attendance
              </button>
            </div>
          </section>
        </>
      )}

      <section aria-labelledby="history-heading">
        <h2 id="history-heading" className="text-lg font-bold">Recent sessions</h2>
        {history.length === 0 ? (
          <p className="mt-2 text-muted">No attendance taken yet.</p>
        ) : (
          <div className="mt-2 overflow-x-auto rounded-md border border-line bg-white">
            <table className="w-full min-w-[30rem] text-left text-sm">
              <thead className="border-b border-line text-muted">
                <tr>
                  <th scope="col" className="px-4 py-2 font-bold">Date</th>
                  <th scope="col" className="px-4 py-2 font-bold">Class</th>
                  <th scope="col" className="px-4 py-2 font-bold">Present</th>
                </tr>
              </thead>
              <tbody>
                {history.map((h) => (
                  <tr key={h.id} className="border-b border-line last:border-0">
                    <td className="px-4 py-2">{h.sessionDate}</td>
                    <td className="px-4 py-2">{h.courseCode}, {h.sectionName}{h.status === 'OPEN' ? ' (open)' : ''}</td>
                    <td className="px-4 py-2">{h.present} of {h.enrolled}</td>
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
