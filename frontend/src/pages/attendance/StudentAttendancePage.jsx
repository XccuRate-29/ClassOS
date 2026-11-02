import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client.js';
import { deviceHeaders } from '../../lib/device.js';

export default function StudentAttendancePage() {
  const [open, setOpen] = useState([]);
  const [summary, setSummary] = useState(null);
  const [device, setDevice] = useState(null);
  const [codes, setCodes] = useState({});
  const [results, setResults] = useState({});
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [o, s, d] = await Promise.all([
        api.get('/student/attendance/open'),
        api.get('/student/attendance/summary'),
        api.get('/student/attendance/device', { headers: deviceHeaders() }),
      ]);
      setOpen(o);
      setSummary(s);
      setDevice(d);
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  async function submit(e, sessionId) {
    e.preventDefault();
    const code = (codes[sessionId] || '').trim();
    try {
      const r = await api.post('/student/attendance/mark', { sessionId, code, method: 'OTP' }, { headers: deviceHeaders() });
      setResults((x) => ({ ...x, [sessionId]: { ok: true, text: r.detail } }));
      load();
    } catch (err) {
      setResults((x) => ({ ...x, [sessionId]: { ok: false, text: err.message } }));
    }
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold">Attendance</h1>
        <p className="text-muted">Scan the code your teacher shows with your phone camera, or type it here.</p>
      </div>

      {error && <p role="alert" className="text-danger">{error}</p>}

      {device && (
        <p className="text-sm">
          {!device.registered && 'This phone will be registered to you the first time you mark attendance. Use the same phone every time.'}
          {device.registered && device.thisDevice && 'This is your registered phone.'}
          {device.registered && !device.thisDevice && (
            <span className="text-danger">
              Your account is registered to a different phone, so marking here won't work. If you changed phones, ask the admin to reset it.
            </span>
          )}
        </p>
      )}

      <section aria-labelledby="open-heading">
        <h2 id="open-heading" className="text-lg font-bold">Open now</h2>
        {open.length === 0 ? (
          <p className="mt-2 text-muted">No attendance is open for your classes right now.</p>
        ) : (
          <ul className="mt-2 space-y-3">
            {open.map((s) => (
              <li key={s.sessionId} className="rounded-md border border-line bg-white p-4">
                <p className="font-bold">{s.courseCode} {s.courseTitle}</p>
                <p className="text-sm text-muted">{s.teacherName}{s.roomCode ? `, ${s.roomCode}` : ''}</p>
                {s.alreadyMarked ? (
                  <p className="mt-2 text-ok">You're marked for this class.</p>
                ) : (
                  <form onSubmit={(e) => submit(e, s.sessionId)} className="mt-3 flex flex-wrap items-end gap-3">
                    <div>
                      <label htmlFor={`code-${s.sessionId}`} className="font-bold">Code on the screen</label>
                      <input id={`code-${s.sessionId}`} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}"
                             maxLength={6} value={codes[s.sessionId] || ''}
                             onChange={(e) => setCodes((c) => ({ ...c, [s.sessionId]: e.target.value.replace(/\D/g, '') }))}
                             className="mt-1 block w-40 rounded border border-line bg-white px-3 py-2 text-xl tracking-[0.2em]" />
                    </div>
                    <button type="submit" disabled={(codes[s.sessionId] || '').length !== 6}
                            className="rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep disabled:opacity-50">
                      Mark me present
                    </button>
                  </form>
                )}
                {results[s.sessionId] && (
                  <p role="status" className={`mt-2 ${results[s.sessionId].ok ? 'text-ok' : 'text-danger'}`}>
                    {results[s.sessionId].text}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {summary && (
        <section aria-labelledby="summary-heading">
          <h2 id="summary-heading" className="text-lg font-bold">This term</h2>
          <p className="text-sm text-muted">The minimum required is {summary.minimumPercent}% in each course.</p>
          <div className="mt-2 overflow-x-auto rounded-md border border-line bg-white">
            <table className="w-full min-w-[30rem] text-left text-sm">
              <thead className="border-b border-line text-muted">
                <tr>
                  <th scope="col" className="px-4 py-2 font-bold">Course</th>
                  <th scope="col" className="px-4 py-2 font-bold">Attended</th>
                  <th scope="col" className="px-4 py-2 font-bold">Percent</th>
                </tr>
              </thead>
              <tbody>
                {summary.courses.map((c) => (
                  <tr key={c.offeringId} className="border-b border-line last:border-0">
                    <td className="px-4 py-2">{c.courseCode} {c.courseTitle}</td>
                    <td className="px-4 py-2">{c.present} of {c.held}</td>
                    <td className={`px-4 py-2 ${c.belowMinimum ? 'font-bold text-danger' : ''}`}>
                      {c.percent == null ? 'No classes yet' : `${c.percent}%${c.belowMinimum ? ' (below minimum)' : ''}`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
