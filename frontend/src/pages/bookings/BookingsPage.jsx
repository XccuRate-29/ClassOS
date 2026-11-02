import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client.js';
import { hhmm, todayIso } from '../../lib/time.js';

const EMPTY = { roomId: '', date: '', startTime: '', endTime: '', purpose: '' };

export default function BookingsPage() {
  const [rooms, setRooms] = useState([]);
  const [form, setForm] = useState({ ...EMPTY, date: todayIso() });
  const [busy, setBusy] = useState(null);
  const [mine, setMine] = useState([]);
  const [error, setError] = useState('');
  const [conflicts, setConflicts] = useState([]);
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));

  const loadMine = useCallback(() => api.get('/bookings/mine').then(setMine).catch((e) => setError(e.message)), []);

  useEffect(() => {
    api.get('/bookings/rooms').then(setRooms).catch((e) => setError(e.message));
    loadMine();
  }, [loadMine]);

  // Show what's already happening in the chosen room that day.
  useEffect(() => {
    if (!form.roomId || !form.date) { setBusy(null); return undefined; }
    let cancelled = false;
    api.get(`/bookings/room-day?roomId=${form.roomId}&date=${form.date}`)
      .then((d) => !cancelled && setBusy(d.busy))
      .catch(() => !cancelled && setBusy(null));
    return () => { cancelled = true; };
  }, [form.roomId, form.date, notice]);

  async function submit(e) {
    e.preventDefault();
    setError('');
    setConflicts([]);
    setNotice('');
    setSaving(true);
    try {
      const b = await api.post('/bookings', { ...form, roomId: Number(form.roomId) });
      setNotice(`Booked ${b.roomCode} on ${b.date}, ${hhmm(b.startTime)}–${hhmm(b.endTime)}.`);
      setForm((f) => ({ ...f, startTime: '', endTime: '', purpose: '' }));
      loadMine();
    } catch (err) {
      setError(err.message);
      setConflicts(err.data?.conflicts || []);
    } finally {
      setSaving(false);
    }
  }

  async function cancel(id) {
    setError('');
    try {
      await api.del(`/bookings/${id}`);
      setNotice('Booking cancelled.');
      loadMine();
    } catch (err) {
      setError(err.message);
    }
  }

  const input = 'mt-1 w-full rounded border border-line bg-white px-3 py-2';

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold">Room bookings</h1>
        <p className="text-muted">Book a room or lab for an extra session. Weekly classes always come first.</p>
      </div>

      <div className="grid gap-6 lg:grid-cols-[3fr_2fr]">
        <form onSubmit={submit} className="rounded-md border border-line bg-white p-5">
          <h2 className="text-lg font-bold">Book a room</h2>
          <div className="mt-4 grid gap-4 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <label htmlFor="b-room" className="font-bold">Room</label>
              <select id="b-room" value={form.roomId} onChange={set('roomId')} className={input} required>
                <option value="">Choose…</option>
                {rooms.map((r) => <option key={r.id} value={r.id}>{r.code}, {r.building} ({r.capacity} seats)</option>)}
              </select>
            </div>
            <div>
              <label htmlFor="b-date" className="font-bold">Date</label>
              <input id="b-date" type="date" min={todayIso()} value={form.date} onChange={set('date')} className={input} required />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="b-start" className="font-bold">From</label>
                <input id="b-start" type="time" value={form.startTime} onChange={set('startTime')} className={input} required />
              </div>
              <div>
                <label htmlFor="b-end" className="font-bold">To</label>
                <input id="b-end" type="time" value={form.endTime} onChange={set('endTime')} className={input} required />
              </div>
            </div>
            <div className="sm:col-span-2">
              <label htmlFor="b-purpose" className="font-bold">Purpose</label>
              <input id="b-purpose" value={form.purpose} onChange={set('purpose')} maxLength={200}
                     placeholder="e.g. Make-up lab for CSE-3B" className={input} required />
            </div>
          </div>

          <div aria-live="polite">
            {notice && <p role="status" className="mt-4 text-ok">{notice}</p>}
            {error && <p role="alert" className="mt-4 text-danger">{error}</p>}
            {conflicts.length > 0 && (
              <ul className="mt-2 space-y-1 border-l-4 border-danger pl-3 text-sm">
                {conflicts.map((c, i) => (
                  <li key={i}>{hhmm(c.startTime)}–{hhmm(c.endTime)}: {c.description}</li>
                ))}
              </ul>
            )}
          </div>

          <button type="submit" disabled={saving}
                  className="mt-5 rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep disabled:opacity-50">
            {saving ? 'Booking…' : 'Book room'}
          </button>
        </form>

        <section className="rounded-md border border-line bg-white p-5" aria-labelledby="busy-heading">
          <h2 id="busy-heading" className="text-lg font-bold">Already in use that day</h2>
          {!busy && <p className="mt-2 text-muted">Choose a room and date to see when it's taken.</p>}
          {busy && busy.length === 0 && <p className="mt-2 text-ok">Free all day.</p>}
          {busy && busy.length > 0 && (
            <ul className="mt-2 space-y-2 text-sm">
              {busy.map((b, i) => (
                <li key={i}>
                  <span className="font-bold">{hhmm(b.startTime)}–{hhmm(b.endTime)}</span>{' '}
                  {b.description}{b.type === 'WEEKLY_CLASS' ? ' (weekly class)' : ''}
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section aria-labelledby="mine-heading">
        <h2 id="mine-heading" className="text-lg font-bold">Your upcoming bookings</h2>
        {mine.length === 0 ? (
          <p className="mt-2 text-muted">You have no upcoming bookings.</p>
        ) : (
          <ul className="mt-2 divide-y divide-line border-y border-line">
            {mine.map((b) => (
              <li key={b.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <span>
                  <span className="font-bold">{b.roomCode}</span>, {b.date}, {hhmm(b.startTime)}–{hhmm(b.endTime)}: {b.purpose}
                </span>
                <button type="button" onClick={() => cancel(b.id)} className="rounded border border-line px-3 py-1.5 text-sm">
                  Cancel booking
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
