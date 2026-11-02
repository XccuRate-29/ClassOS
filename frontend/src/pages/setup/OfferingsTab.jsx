import { useState } from 'react';
import { api } from '../../api/client.js';
import { Feedback, Field, inputClass, primaryButton, secondaryButton, useAction, useLoad } from './common.jsx';

export function OfferingsTab({ term }) {
  const courses = useLoad(() => api.get('/admin/setup/courses'), []);
  const sections = useLoad(() => api.get(`/admin/setup/sections?termId=${term.id}`), [term.id]);
  const teachers = useLoad(() => api.get('/admin/setup/teachers'), []);
  const offerings = useLoad(() => api.get(`/admin/setup/offerings?termId=${term.id}`), [term.id]);
  const [form, setForm] = useState({ courseId: '', sectionId: '', teacherId: '' });
  const act = useAction();
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function add(e) {
    e.preventDefault();
    const body = { termId: term.id, courseId: Number(form.courseId), sectionId: Number(form.sectionId), teacherId: Number(form.teacherId) };
    if (await act.run(() => api.post('/admin/setup/offerings', body), 'Added.')) {
      setForm((f) => ({ ...f, courseId: '' }));
      offerings.reload();
    }
  }

  async function changeTeacher(o, teacherId) {
    if (await act.run(() => api.put(`/admin/setup/offerings/${o.id}/teacher`, { teacherId: Number(teacherId) }),
      `${o.courseCode} for ${o.sectionName} reassigned.`)) offerings.reload();
  }

  async function remove(o) {
    if (await act.run(() => api.del(`/admin/setup/offerings/${o.id}`), `${o.courseCode} removed from ${o.sectionName}.`)) offerings.reload();
  }

  return (
    <div className="space-y-6">
      <form onSubmit={add} className="rounded-md border border-line bg-white p-5">
        <h2 className="text-lg font-bold">Assign a course to a section</h2>
        <div className="mt-4 grid gap-4 sm:grid-cols-3">
          <Field id="o-section" label="Section">
            <select id="o-section" value={form.sectionId} onChange={set('sectionId')} className={inputClass} required>
              <option value="">Choose…</option>
              {sections.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </Field>
          <Field id="o-course" label="Course">
            <select id="o-course" value={form.courseId} onChange={set('courseId')} className={inputClass} required>
              <option value="">Choose…</option>
              {courses.data?.map((c) => <option key={c.id} value={c.id}>{c.code} {c.title}</option>)}
            </select>
          </Field>
          <Field id="o-teacher" label="Teacher">
            <select id="o-teacher" value={form.teacherId} onChange={set('teacherId')} className={inputClass} required>
              <option value="">Choose…</option>
              {teachers.data?.map((t) => <option key={t.id} value={t.id}>{t.fullName}</option>)}
            </select>
          </Field>
        </div>
        <button type="submit" disabled={act.busy} className={`${primaryButton} mt-4`}>Assign</button>
      </form>

      <Feedback notice={act.notice} error={act.error} />
      <p className="text-sm text-muted">
        Changing a teacher also moves their timetabled classes. If the new teacher is busy at any of those times, the change is refused.
      </p>

      {offerings.data?.length === 0 && <p className="text-muted">Nothing assigned in {term.name} yet.</p>}
      {offerings.data?.length > 0 && (
        <div className="overflow-x-auto rounded-md border border-line bg-white">
          <table className="w-full min-w-[40rem] text-left text-sm">
            <thead className="border-b border-line text-muted">
              <tr>
                <th scope="col" className="px-4 py-2 font-bold">Section</th>
                <th scope="col" className="px-4 py-2 font-bold">Course</th>
                <th scope="col" className="px-4 py-2 font-bold">Teacher</th>
                <th scope="col" className="px-4 py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {offerings.data.map((o) => (
                <tr key={o.id} className="border-b border-line last:border-0">
                  <td className="px-4 py-2 font-bold">{o.sectionName}</td>
                  <td className="px-4 py-2">{o.courseCode} {o.courseTitle}</td>
                  <td className="px-4 py-2">
                    <label className="sr-only" htmlFor={`t-${o.id}`}>Teacher for {o.courseCode}, {o.sectionName}</label>
                    <select id={`t-${o.id}`} value={o.teacherId} onChange={(e) => changeTeacher(o, e.target.value)}
                            className="rounded border border-line bg-white px-2 py-1">
                      {teachers.data?.map((t) => <option key={t.id} value={t.id}>{t.fullName}</option>)}
                    </select>
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button type="button" onClick={() => remove(o)} className={secondaryButton}>Remove</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
