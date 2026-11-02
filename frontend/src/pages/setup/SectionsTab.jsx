import { useState } from 'react';
import { api } from '../../api/client.js';
import { Feedback, Field, inputClass, primaryButton, secondaryButton, useAction, useLoad } from './common.jsx';

export function SectionsTab({ term }) {
  const departments = useLoad(() => api.get('/admin/setup/departments'), []);
  const sections = useLoad(() => api.get(`/admin/setup/sections?termId=${term.id}`), [term.id]);
  const [form, setForm] = useState({ departmentId: '', name: '', semester: '1', strength: '60' });
  const [editingId, setEditingId] = useState(null);
  const [openId, setOpenId] = useState(null);
  const act = useAction();
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function save(e) {
    e.preventDefault();
    const body = { termId: term.id, departmentId: Number(form.departmentId), name: form.name,
      semester: Number(form.semester), strength: Number(form.strength) };
    const ok = await act.run(() => (editingId ? api.put(`/admin/setup/sections/${editingId}`, body) : api.post('/admin/setup/sections', body)),
      editingId ? 'Section updated.' : 'Section added.');
    if (ok) { setForm((f) => ({ ...f, name: '' })); setEditingId(null); sections.reload(); }
  }

  async function remove(s) {
    if (await act.run(() => api.del(`/admin/setup/sections/${s.id}`), `${s.name} removed.`)) sections.reload();
  }

  return (
    <div className="space-y-6">
      <form onSubmit={save} className="rounded-md border border-line bg-white p-5">
        <h2 className="text-lg font-bold">{editingId ? 'Edit section' : `Add a section to ${term.name}`}</h2>
        <div className="mt-4 grid gap-4 sm:grid-cols-4">
          <Field id="s-dept" label="Department">
            <select id="s-dept" value={form.departmentId} onChange={set('departmentId')} className={inputClass} required disabled={!!editingId}>
              <option value="">Choose…</option>
              {departments.data?.map((d) => <option key={d.id} value={d.id}>{d.code}</option>)}
            </select>
          </Field>
          <Field id="s-name" label="Name" error={act.fields.name}>
            <input id="s-name" value={form.name} onChange={set('name')} placeholder="CSE-3A" className={inputClass} required />
          </Field>
          <Field id="s-sem" label="Semester" error={act.fields.semester}>
            <input id="s-sem" type="number" min="1" max="10" value={form.semester} onChange={set('semester')} className={inputClass} required />
          </Field>
          <Field id="s-str" label="Planned size" error={act.fields.strength}>
            <input id="s-str" type="number" min="1" value={form.strength} onChange={set('strength')} className={inputClass} required />
          </Field>
        </div>
        <p className="mt-2 text-sm text-muted">The timetable uses planned size to choose rooms that fit.</p>
        <Feedback notice={act.notice} error={act.error} />
        <div className="mt-4 flex gap-3">
          <button type="submit" disabled={act.busy} className={primaryButton}>{editingId ? 'Save section' : 'Add section'}</button>
          {editingId && <button type="button" onClick={() => setEditingId(null)} className={secondaryButton}>Cancel</button>}
        </div>
      </form>

      {sections.error && <p role="alert" className="text-danger">{sections.error}</p>}
      {sections.data?.length === 0 && <p className="text-muted">No sections in {term.name} yet.</p>}
      <ul className="space-y-3">
        {sections.data?.map((s) => (
          <li key={s.id} className="rounded-md border border-line bg-white p-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <span>
                <span className="font-bold">{s.name}</span>{' '}
                <span className="text-muted">{s.departmentCode}, semester {s.semester}, {s.enrolled} of {s.strength} students enrolled</span>
              </span>
              <span className="flex gap-2">
                <button type="button" className={secondaryButton} aria-expanded={openId === s.id}
                        onClick={() => setOpenId(openId === s.id ? null : s.id)}>
                  {openId === s.id ? 'Hide students' : 'Students'}
                </button>
                <button type="button" className={secondaryButton} onClick={() => {
                  setEditingId(s.id);
                  setForm({ departmentId: String(s.departmentId), name: s.name, semester: String(s.semester), strength: String(s.strength) });
                }}>Edit</button>
                <button type="button" className={secondaryButton} onClick={() => remove(s)}>Remove</button>
              </span>
            </div>
            {openId === s.id && <Students section={s} onChange={sections.reload} />}
          </li>
        ))}
      </ul>
    </div>
  );
}

function Students({ section, onChange }) {
  const students = useLoad(() => api.get(`/admin/setup/sections/${section.id}/students`), [section.id]);
  const [emails, setEmails] = useState('');
  const [result, setResult] = useState(null);
  const act = useAction();

  async function enroll(e) {
    e.preventDefault();
    const list = emails.split(/[\s,;]+/).map((x) => x.trim()).filter(Boolean);
    const r = await act.run(() => api.post(`/admin/setup/sections/${section.id}/students`, { emails: list }));
    if (r) { setResult(r); setEmails(''); students.reload(); onChange(); }
  }

  async function remove(st) {
    if (await act.run(() => api.del(`/admin/setup/sections/${section.id}/students/${st.id}`), `${st.fullName} removed from ${section.name}.`)) {
      students.reload();
      onChange();
    }
  }

  return (
    <div className="mt-4 space-y-4 border-t border-line pt-4">
      <form onSubmit={enroll}>
        <Field id={`emails-${section.id}`} label="Add students by email">
          <textarea id={`emails-${section.id}`} rows={4} value={emails} onChange={(e) => setEmails(e.target.value)}
                    placeholder="One per line, or separated by commas" className={inputClass} />
        </Field>
        <p className="mt-1 text-sm text-muted">Students must have an account first. Import them on the People page.</p>
        <button type="submit" disabled={act.busy || !emails.trim()} className={`${primaryButton} mt-3`}>Add to {section.name}</button>
      </form>
      <Feedback notice={act.notice} error={act.error} />
      {result && (
        <div className="text-sm" role="status">
          <p className="font-bold">Added {result.added}.</p>
          <Listed label="Already in this section" items={result.alreadyInSection} />
          <Listed label="In another section this term (remove them there first)" items={result.inOtherSection} />
          <Listed label="No account with this email" items={result.notFound} />
          <Listed label="Not a student account" items={result.notStudents} />
        </div>
      )}
      {students.data?.length > 0 && (
        <ul className="divide-y divide-line border-y border-line text-sm">
          {students.data.map((st) => (
            <li key={st.id} className="flex items-center justify-between gap-3 py-2">
              <span>{st.fullName} <span className="text-muted">{st.email}</span></span>
              <button type="button" onClick={() => remove(st)} className={secondaryButton}
                      aria-label={`Remove ${st.fullName} from ${section.name}`}>Remove</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Listed({ label, items }) {
  if (!items?.length) return null;
  return <p><span className="font-bold">{label}:</span> {items.join(', ')}</p>;
}
