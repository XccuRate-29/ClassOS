import { useState } from 'react';
import { api } from '../../api/client.js';
import { Feedback, Field, inputClass, primaryButton, secondaryButton, useAction, useLoad } from './common.jsx';

const EMPTY = { departmentId: '', code: '', title: '', credits: '4', lectureHoursPerWeek: '3', labHoursPerWeek: '0' };

export function CoursesTab() {
  const departments = useLoad(() => api.get('/admin/setup/departments'), []);
  const courses = useLoad(() => api.get('/admin/setup/courses'), []);
  const [form, setForm] = useState(EMPTY);
  const [editingId, setEditingId] = useState(null);
  const [dept, setDept] = useState({ code: '', name: '' });
  const act = useAction();
  const deptAct = useAction();
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function save(e) {
    e.preventDefault();
    const body = {
      ...form,
      departmentId: Number(form.departmentId),
      credits: Number(form.credits),
      lectureHoursPerWeek: Number(form.lectureHoursPerWeek),
      labHoursPerWeek: Number(form.labHoursPerWeek),
    };
    const ok = await act.run(() => (editingId ? api.put(`/admin/setup/courses/${editingId}`, body) : api.post('/admin/setup/courses', body)),
      editingId ? 'Course updated. New hours apply the next time you generate the timetable.' : 'Course added.');
    if (ok) { setForm({ ...EMPTY, departmentId: form.departmentId }); setEditingId(null); courses.reload(); }
  }

  async function addDepartment(e) {
    e.preventDefault();
    if (await deptAct.run(() => api.post('/admin/setup/departments', dept), 'Department added.')) {
      setDept({ code: '', name: '' });
      departments.reload();
    }
  }

  return (
    <div className="space-y-6">
      <form onSubmit={addDepartment} className="rounded-md border border-line bg-white p-5">
        <h2 className="text-lg font-bold">Departments</h2>
        <p className="text-sm text-muted">
          {departments.data?.length ? departments.data.map((d) => `${d.code} (${d.name})`).join(', ') : 'None yet.'}
        </p>
        <div className="mt-3 grid gap-4 sm:grid-cols-[1fr_3fr_auto] sm:items-end">
          <Field id="d-code" label="Code" error={deptAct.fields.code}>
            <input id="d-code" value={dept.code} onChange={(e) => setDept((d) => ({ ...d, code: e.target.value }))} placeholder="CSE" className={inputClass} required />
          </Field>
          <Field id="d-name" label="Name" error={deptAct.fields.name}>
            <input id="d-name" value={dept.name} onChange={(e) => setDept((d) => ({ ...d, name: e.target.value }))} className={inputClass} required />
          </Field>
          <button type="submit" disabled={deptAct.busy} className={primaryButton}>Add department</button>
        </div>
        <Feedback notice={deptAct.notice} error={deptAct.error} />
      </form>

      <form onSubmit={save} className="rounded-md border border-line bg-white p-5">
        <h2 className="text-lg font-bold">{editingId ? 'Edit course' : 'Add a course'}</h2>
        <div className="mt-4 grid gap-4 sm:grid-cols-6">
          <Field id="c-dept" label="Department" className="sm:col-span-2">
            <select id="c-dept" value={form.departmentId} onChange={set('departmentId')} className={inputClass} required>
              <option value="">Choose…</option>
              {departments.data?.map((d) => <option key={d.id} value={d.id}>{d.code}</option>)}
            </select>
          </Field>
          <Field id="c-code" label="Code" error={act.fields.code} className="sm:col-span-1">
            <input id="c-code" value={form.code} onChange={set('code')} placeholder="TCS-302" className={inputClass} required />
          </Field>
          <Field id="c-title" label="Title" error={act.fields.title} className="sm:col-span-3">
            <input id="c-title" value={form.title} onChange={set('title')} className={inputClass} required />
          </Field>
          <Field id="c-credits" label="Credits" error={act.fields.credits}>
            <input id="c-credits" type="number" min="1" max="10" value={form.credits} onChange={set('credits')} className={inputClass} required />
          </Field>
          <Field id="c-lec" label="Lecture hours / week" error={act.fields.lectureHoursPerWeek} className="sm:col-span-2">
            <input id="c-lec" type="number" min="0" max="40" value={form.lectureHoursPerWeek} onChange={set('lectureHoursPerWeek')} className={inputClass} required />
          </Field>
          <Field id="c-lab" label="Lab hours / week" error={act.fields.labHoursPerWeek} className="sm:col-span-2">
            <input id="c-lab" type="number" min="0" max="40" value={form.labHoursPerWeek} onChange={set('labHoursPerWeek')} className={inputClass} required />
          </Field>
        </div>
        <p className="mt-2 text-sm text-muted">Lab hours are scheduled in 2-period blocks.</p>
        <Feedback notice={act.notice} error={act.error} />
        <div className="mt-4 flex gap-3">
          <button type="submit" disabled={act.busy} className={primaryButton}>{editingId ? 'Save course' : 'Add course'}</button>
          {editingId && <button type="button" onClick={() => { setEditingId(null); setForm(EMPTY); }} className={secondaryButton}>Cancel</button>}
        </div>
      </form>

      {courses.error && <p role="alert" className="text-danger">{courses.error}</p>}
      {courses.data && (
        <div className="overflow-x-auto rounded-md border border-line bg-white">
          <table className="w-full min-w-[36rem] text-left text-sm">
            <thead className="border-b border-line text-muted">
              <tr>
                <th scope="col" className="px-4 py-2 font-bold">Course</th>
                <th scope="col" className="px-4 py-2 font-bold">Department</th>
                <th scope="col" className="px-4 py-2 font-bold">Credits</th>
                <th scope="col" className="px-4 py-2 font-bold">Hours / week</th>
                <th scope="col" className="px-4 py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {courses.data.map((c) => (
                <tr key={c.id} className="border-b border-line last:border-0">
                  <td className="px-4 py-2"><span className="font-bold">{c.code}</span> {c.title}</td>
                  <td className="px-4 py-2">{c.departmentCode}</td>
                  <td className="px-4 py-2">{c.credits}</td>
                  <td className="px-4 py-2">{c.lectureHoursPerWeek} lecture, {c.labHoursPerWeek} lab</td>
                  <td className="px-4 py-2 text-right">
                    <button type="button" className={secondaryButton} onClick={() => {
                      setEditingId(c.id);
                      setForm({ departmentId: String(c.departmentId), code: c.code, title: c.title, credits: String(c.credits),
                        lectureHoursPerWeek: String(c.lectureHoursPerWeek), labHoursPerWeek: String(c.labHoursPerWeek) });
                    }}>Edit</button>
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
