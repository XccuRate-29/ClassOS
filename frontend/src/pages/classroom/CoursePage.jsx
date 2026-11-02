import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client.js';
import { DownloadButton } from '../../components/DownloadButton.jsx';
import { PRIORITY_LABEL, dateTime, fileSize, localInputToIso, marks } from '../../lib/format.js';
import { Feedback, Field, inputClass, primaryButton, secondaryButton, useAction, useLoad } from '../setup/common.jsx';

export default function CoursePage() {
  const { offeringId } = useParams();
  const ws = useLoad(() => api.get(`/workspace/offerings/${offeringId}`), [offeringId]);

  if (ws.error) return <p role="alert" className="text-danger">{ws.error}</p>;
  if (!ws.data) return <p className="text-muted" role="status">Loading course…</p>;
  const { course, canTeach, materials, assignments } = ws.data;

  return (
    <div className="space-y-8">
      <div>
        <Link to="/classroom" className="text-sm underline">All courses</Link>
        <h1 className="mt-1 text-3xl font-bold">{course.courseCode} {course.courseTitle}</h1>
        <p className="text-muted">{course.sectionName}, {course.teacherName}</p>
      </div>

      <section aria-labelledby="materials-heading" className="space-y-3">
        <h2 id="materials-heading" className="text-lg font-bold">Materials</h2>
        {canTeach && <MaterialForm offeringId={offeringId} onAdded={ws.reload} />}
        {materials.length === 0 && <p className="text-muted">No materials shared yet.</p>}
        <ul className="divide-y divide-line border-y border-line">
          {materials.map((m) => (
            <li key={m.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
              <span>
                <span className="font-bold">{m.title}</span>
                <span className="block text-sm text-muted">{m.fileName}, {fileSize(m.sizeBytes)}, {dateTime(m.createdAt)}</span>
                {m.description && <span className="block text-sm">{m.description}</span>}
              </span>
              <span className="flex gap-2">
                <DownloadButton path={`/workspace/materials/${m.id}/file`} label="Download" />
                {canTeach && <DeleteMaterial id={m.id} onDone={ws.reload} />}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="assignments-heading" className="space-y-3">
        <h2 id="assignments-heading" className="text-lg font-bold">Assignments</h2>
        {canTeach && <AssignmentForm offeringId={offeringId} onCreated={ws.reload} />}
        {assignments.length === 0 && <p className="text-muted">No assignments yet.</p>}
        <ul className="space-y-3">
          {assignments.map((a) => (
            <li key={a.id} className="rounded-md border border-line bg-white p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <Link to={`/classroom/assignments/${a.id}`} className="font-bold underline-offset-4 hover:underline">{a.title}</Link>
                  <p className="text-sm text-muted">
                    Due {dateTime(a.dueAt)}, {marks(a.maxMarks)} marks{a.acceptLate ? ', late work accepted' : ', no late work'}
                  </p>
                </div>
                {canTeach && (
                  <span className="text-sm">
                    {a.submitted} submitted, {a.graded} graded, {PRIORITY_LABEL[a.priority].toLowerCase()} priority
                  </span>
                )}
              </div>
              {!canTeach && <StudentStatus a={a} />}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

function StudentStatus({ a }) {
  const m = a.mine;
  if (!m) return <p className="mt-2 text-sm font-bold">Not submitted yet.</p>;
  return (
    <p className="mt-2 text-sm">
      {m.status === 'GRADED'
        ? <span className="font-bold text-ok">Graded: {marks(m.marks)} / {marks(a.maxMarks)}{m.feedback ? `. ${m.feedback}` : ''}</span>
        : <span>Submitted {dateTime(m.submittedAt)}{m.late ? ' (late)' : ''}, waiting to be graded.</span>}
    </p>
  );
}

function DeleteMaterial({ id, onDone }) {
  const act = useAction();
  return (
    <span className="inline-flex flex-col">
      <button type="button" className={secondaryButton}
              onClick={async () => { if (await act.run(() => api.del(`/workspace/materials/${id}`))) onDone(); }}>
        Remove
      </button>
      {act.error && <span role="alert" className="text-sm text-danger">{act.error}</span>}
    </span>
  );
}

function MaterialForm({ offeringId, onAdded }) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [file, setFile] = useState(null);
  const [inputKey, setInputKey] = useState(0);
  const act = useAction();

  async function submit(e) {
    e.preventDefault();
    const fd = new FormData();
    fd.append('title', title);
    if (description) fd.append('description', description);
    fd.append('file', file);
    if (await act.run(() => api.upload(`/workspace/offerings/${offeringId}/materials`, fd), 'Shared with the class.')) {
      setTitle('');
      setDescription('');
      setFile(null);
      setInputKey((k) => k + 1);
      onAdded();
    }
  }

  return (
    <form onSubmit={submit} className="rounded-md border border-line bg-white p-4">
      <div className="grid gap-4 sm:grid-cols-3">
        <Field id="m-title" label="Title">
          <input id="m-title" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} className={inputClass} required />
        </Field>
        <Field id="m-desc" label="Note (optional)">
          <input id="m-desc" value={description} onChange={(e) => setDescription(e.target.value)} className={inputClass} />
        </Field>
        <Field id="m-file" label="File">
          <input key={inputKey} id="m-file" type="file" onChange={(e) => setFile(e.target.files?.[0] || null)} className="mt-2 block w-full" required />
        </Field>
      </div>
      <Feedback notice={act.notice} error={act.error} />
      <button type="submit" disabled={act.busy || !file || !title} className={`${primaryButton} mt-3`}>
        {act.busy ? 'Uploading…' : 'Share material'}
      </button>
    </form>
  );
}

function AssignmentForm({ offeringId, onCreated }) {
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ title: '', instructions: '', dueAt: '', gradeBy: '', maxMarks: '10',
    acceptLate: true, priority: '2', estMinutes: '10' });
  const [file, setFile] = useState(null);
  const act = useAction();
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value }));

  async function submit(e) {
    e.preventDefault();
    const data = {
      title: form.title, instructions: form.instructions, dueAt: localInputToIso(form.dueAt),
      gradeBy: localInputToIso(form.gradeBy), maxMarks: Number(form.maxMarks), acceptLate: form.acceptLate,
      priority: Number(form.priority), estMinutes: Number(form.estMinutes),
    };
    const fd = new FormData();
    fd.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }));
    if (file) fd.append('file', file);
    if (await act.run(() => api.upload(`/workspace/offerings/${offeringId}/assignments`, fd), 'Assignment posted.')) {
      setOpen(false);
      onCreated();
    }
  }

  if (!open) return <button type="button" onClick={() => setOpen(true)} className={primaryButton}>New assignment</button>;
  return (
    <form onSubmit={submit} className="rounded-md border border-line bg-white p-4">
      <div className="grid gap-4 sm:grid-cols-4">
        <Field id="a-title" label="Title" error={act.fields.title} className="sm:col-span-4">
          <input id="a-title" value={form.title} onChange={set('title')} maxLength={200} className={inputClass} required />
        </Field>
        <Field id="a-instr" label="Instructions" className="sm:col-span-4">
          <textarea id="a-instr" rows={4} value={form.instructions} onChange={set('instructions')} className={inputClass} />
        </Field>
        <Field id="a-due" label="Due" error={act.fields.dueAt}>
          <input id="a-due" type="datetime-local" value={form.dueAt} onChange={set('dueAt')} className={inputClass} required />
        </Field>
        <Field id="a-gradeby" label="Grade by (optional)">
          <input id="a-gradeby" type="datetime-local" value={form.gradeBy} onChange={set('gradeBy')} className={inputClass} />
        </Field>
        <Field id="a-max" label="Maximum marks" error={act.fields.maxMarks}>
          <input id="a-max" type="number" min="0.5" step="0.5" value={form.maxMarks} onChange={set('maxMarks')} className={inputClass} required />
        </Field>
        <Field id="a-file" label="Attachment (optional)">
          <input id="a-file" type="file" onChange={(e) => setFile(e.target.files?.[0] || null)} className="mt-2 block w-full" />
        </Field>
        <Field id="a-priority" label="Grading priority">
          <select id="a-priority" value={form.priority} onChange={set('priority')} className={inputClass}>
            <option value="1">High</option><option value="2">Normal</option><option value="3">Low</option>
          </select>
        </Field>
        <Field id="a-est" label="Minutes to grade one" error={act.fields.estMinutes}>
          <input id="a-est" type="number" min="1" max="240" value={form.estMinutes} onChange={set('estMinutes')} className={inputClass} required />
        </Field>
        <label className="flex items-center gap-2 sm:col-span-2 sm:pt-7">
          <input type="checkbox" checked={form.acceptLate} onChange={set('acceptLate')} /> Accept late submissions (marked late)
        </label>
      </div>
      <p className="mt-2 text-sm text-muted">
        Priority and grading time decide where these submissions appear in your grading queue. Grade-by defaults to a week after the due date.
      </p>
      <Feedback notice={act.notice} error={act.error} />
      <div className="mt-3 flex gap-3">
        <button type="submit" disabled={act.busy} className={primaryButton}>Post assignment</button>
        <button type="button" onClick={() => setOpen(false)} className={secondaryButton}>Cancel</button>
      </div>
    </form>
  );
}
