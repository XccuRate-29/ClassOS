import { useState } from 'react';
import { api } from '../../api/client.js';
import { hhmm } from '../../lib/time.js';
import { Feedback, Field, inputClass, primaryButton, secondaryButton, useAction } from './common.jsx';

const EMPTY = { name: '', startsOn: '', endsOn: '', workingDays: '5' };

// A common college day: 8 periods of 55 minutes with lunch after period 4.
const STANDARD_DAY = [
  ['09:00', '09:55', false], ['09:55', '10:50', false], ['10:50', '11:45', false], ['11:45', '12:40', true],
  ['13:30', '14:25', false], ['14:25', '15:20', false], ['15:20', '16:15', false], ['16:15', '17:10', false],
].map(([startTime, endTime, breakAfter]) => ({ startTime, endTime, breakAfter }));

export function TermsTab({ terms, reload }) {
  const [form, setForm] = useState(EMPTY);
  const [editingId, setEditingId] = useState(null);
  const [gridTermId, setGridTermId] = useState(String((terms.find((t) => t.current) || terms[0] || {}).id || ''));
  const act = useAction();
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function save(e) {
    e.preventDefault();
    const body = { ...form, workingDays: Number(form.workingDays) };
    const ok = await act.run(() => (editingId ? api.put(`/admin/setup/terms/${editingId}`, body) : api.post('/admin/setup/terms', body)),
      editingId ? 'Term updated.' : 'Term created. Now set its period grid below.');
    if (ok) {
      if (!editingId) setGridTermId(String(ok.id));
      setForm(EMPTY);
      setEditingId(null);
      reload();
    }
  }

  async function makeCurrent(t) {
    if (await act.run(() => api.post(`/admin/setup/terms/${t.id}/current`), `${t.name} is now the current term.`)) reload();
  }

  const gridTerm = terms.find((t) => String(t.id) === gridTermId);

  return (
    <div className="space-y-6">
      <form onSubmit={save} className="rounded-md border border-line bg-white p-5">
        <h2 className="text-lg font-bold">{editingId ? 'Edit term' : 'New term'}</h2>
        <div className="mt-4 grid gap-4 sm:grid-cols-4">
          <Field id="t-name" label="Name" error={act.fields.name} className="sm:col-span-2">
            <input id="t-name" value={form.name} onChange={set('name')} placeholder="Odd 2026" className={inputClass} required />
          </Field>
          <Field id="t-start" label="Starts" error={act.fields.startsOn}>
            <input id="t-start" type="date" value={form.startsOn} onChange={set('startsOn')} className={inputClass} required />
          </Field>
          <Field id="t-end" label="Ends" error={act.fields.endsOn}>
            <input id="t-end" type="date" value={form.endsOn} onChange={set('endsOn')} className={inputClass} required />
          </Field>
          <Field id="t-days" label="Teaching days a week">
            <select id="t-days" value={form.workingDays} onChange={set('workingDays')} className={inputClass}>
              <option value="5">5 (Mon–Fri)</option>
              <option value="6">6 (Mon–Sat)</option>
            </select>
          </Field>
        </div>
        <Feedback notice={act.notice} error={act.error} />
        <div className="mt-4 flex gap-3">
          <button type="submit" disabled={act.busy} className={primaryButton}>{editingId ? 'Save term' : 'Create term'}</button>
          {editingId && <button type="button" onClick={() => { setEditingId(null); setForm(EMPTY); }} className={secondaryButton}>Cancel</button>}
        </div>
      </form>

      <section aria-labelledby="terms-heading">
        <h2 id="terms-heading" className="text-lg font-bold">Terms</h2>
        {terms.length === 0 ? <p className="mt-2 text-muted">No terms yet.</p> : (
          <ul className="mt-2 divide-y divide-line border-y border-line">
            {terms.map((t) => (
              <li key={t.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <span>
                  <span className="font-bold">{t.name}</span>{t.current && <span className="ml-2 bg-highlight px-1.5 text-sm">Current</span>}
                  <span className="block text-sm text-muted">
                    {t.startsOn} to {t.endsOn}, {t.workingDays} days a week, {t.periods.length} periods
                  </span>
                </span>
                <span className="flex gap-2">
                  {!t.current && <button type="button" onClick={() => makeCurrent(t)} className={secondaryButton}>Make current</button>}
                  <button type="button" className={secondaryButton} onClick={() => {
                    setEditingId(t.id);
                    setForm({ name: t.name, startsOn: t.startsOn, endsOn: t.endsOn, workingDays: String(t.workingDays) });
                  }}>Edit</button>
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {terms.length > 0 && (
        <section aria-labelledby="grid-heading" className="rounded-md border border-line bg-white p-5">
          <h2 id="grid-heading" className="text-lg font-bold">Period grid</h2>
          <p className="text-sm text-muted">The teaching periods of each day. Lab sessions never run across a break.</p>
          <Field id="grid-term" label="For term" className="mt-3 max-w-xs">
            <select id="grid-term" value={gridTermId} onChange={(e) => setGridTermId(e.target.value)} className={inputClass}>
              {terms.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </Field>
          {gridTerm && <PeriodEditor key={gridTerm.id} term={gridTerm} onSaved={reload} />}
        </section>
      )}
    </div>
  );
}

function PeriodEditor({ term, onSaved }) {
  const [rows, setRows] = useState(term.periods.map((p) => ({
    startTime: hhmm(p.startTime), endTime: hhmm(p.endTime), breakAfter: p.breakAfter,
  })));
  const act = useAction();
  const update = (i, k, v) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, [k]: v } : r)));

  async function save() {
    if (await act.run(() => api.put(`/admin/setup/terms/${term.id}/periods`, { periods: rows }), 'Period grid saved.')) onSaved();
  }

  return (
    <div className="mt-4">
      {rows.length === 0 && <p className="text-muted">No periods yet. Add them one by one, or start from a standard day.</p>}
      <ol className="space-y-2">
        {rows.map((r, i) => (
          <li key={i} className="flex flex-wrap items-end gap-3">
            <span className="w-8 pb-2 font-bold">P{i + 1}</span>
            <Field id={`p${i}-s`} label="Starts">
              <input id={`p${i}-s`} type="time" value={r.startTime} onChange={(e) => update(i, 'startTime', e.target.value)} className={inputClass} />
            </Field>
            <Field id={`p${i}-e`} label="Ends">
              <input id={`p${i}-e`} type="time" value={r.endTime} onChange={(e) => update(i, 'endTime', e.target.value)} className={inputClass} />
            </Field>
            <label className="flex items-center gap-2 pb-2">
              <input type="checkbox" checked={r.breakAfter} onChange={(e) => update(i, 'breakAfter', e.target.checked)} />
              Break after
            </label>
            <button type="button" onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}
                    className={`${secondaryButton} mb-0.5`} aria-label={`Remove period ${i + 1}`}>Remove</button>
          </li>
        ))}
      </ol>
      <div className="mt-4 flex flex-wrap gap-3">
        <button type="button" onClick={() => setRows((rs) => [...rs, { startTime: '', endTime: '', breakAfter: false }])}
                className={secondaryButton}>Add period</button>
        <button type="button" onClick={() => setRows(STANDARD_DAY)} className={secondaryButton}>Use a standard 8-period day</button>
        <button type="button" onClick={save} disabled={act.busy || rows.length === 0 || rows.some((r) => !r.startTime || !r.endTime)}
                className={primaryButton}>Save period grid</button>
      </div>
      <Feedback notice={act.notice} error={act.error} />
    </div>
  );
}
