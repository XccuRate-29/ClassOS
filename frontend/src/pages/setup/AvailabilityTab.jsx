import { useState } from 'react';
import { api } from '../../api/client.js';
import { DAY_NAMES, hhmm } from '../../lib/time.js';
import { Feedback, Field, inputClass, primaryButton, secondaryButton, useAction, useLoad } from './common.jsx';

export function AvailabilityTab({ term }) {
  const teachers = useLoad(() => api.get('/admin/setup/teachers'), []);
  const rows = useLoad(() => api.get(`/admin/setup/unavailability?termId=${term.id}`), [term.id]);
  const [form, setForm] = useState({ teacherId: '', dayOfWeek: '1', startTime: '', endTime: '', reason: '' });
  const act = useAction();
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function add(e) {
    e.preventDefault();
    const body = { ...form, termId: term.id, teacherId: Number(form.teacherId), dayOfWeek: Number(form.dayOfWeek) };
    if (await act.run(() => api.post('/admin/setup/unavailability', body), 'Saved. The generator will avoid this time.')) {
      setForm((f) => ({ ...f, startTime: '', endTime: '', reason: '' }));
      rows.reload();
    }
  }

  async function remove(r) {
    if (await act.run(() => api.del(`/admin/setup/unavailability/${r.id}`), 'Removed.')) rows.reload();
  }

  return (
    <div className="space-y-6">
      <form onSubmit={add} className="rounded-md border border-line bg-white p-5">
        <h2 className="text-lg font-bold">When a teacher can't teach</h2>
        <p className="text-sm text-muted">For example, a weekly department meeting. Applies to every week of {term.name}.</p>
        <div className="mt-4 grid gap-4 sm:grid-cols-5">
          <Field id="u-teacher" label="Teacher" className="sm:col-span-2">
            <select id="u-teacher" value={form.teacherId} onChange={set('teacherId')} className={inputClass} required>
              <option value="">Choose…</option>
              {teachers.data?.map((t) => <option key={t.id} value={t.id}>{t.fullName}</option>)}
            </select>
          </Field>
          <Field id="u-day" label="Day">
            <select id="u-day" value={form.dayOfWeek} onChange={set('dayOfWeek')} className={inputClass}>
              {DAY_NAMES.slice(0, term.workingDays).map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
            </select>
          </Field>
          <Field id="u-start" label="From">
            <input id="u-start" type="time" value={form.startTime} onChange={set('startTime')} className={inputClass} required />
          </Field>
          <Field id="u-end" label="To">
            <input id="u-end" type="time" value={form.endTime} onChange={set('endTime')} className={inputClass} required />
          </Field>
          <Field id="u-reason" label="Reason (optional)" className="sm:col-span-5">
            <input id="u-reason" value={form.reason} onChange={set('reason')} maxLength={200} className={inputClass} />
          </Field>
        </div>
        <Feedback notice={act.notice} error={act.error} />
        <button type="submit" disabled={act.busy} className={`${primaryButton} mt-4`}>Save</button>
      </form>

      {rows.data?.length === 0 && <p className="text-muted">No unavailable times in {term.name}.</p>}
      {rows.data?.length > 0 && (
        <ul className="divide-y divide-line border-y border-line">
          {rows.data.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
              <span>
                <span className="font-bold">{r.teacherName}</span>, {DAY_NAMES[r.dayOfWeek - 1]} {hhmm(r.startTime)}–{hhmm(r.endTime)}
                {r.reason && <span className="text-muted"> ({r.reason})</span>}
              </span>
              <button type="button" onClick={() => remove(r)} className={secondaryButton}>Remove</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
