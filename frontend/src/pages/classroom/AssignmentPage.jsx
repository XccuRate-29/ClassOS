import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client.js';
import { DownloadButton } from '../../components/DownloadButton.jsx';
import { dateTime, marks } from '../../lib/format.js';
import { Feedback, primaryButton, useAction, useLoad } from '../setup/common.jsx';
import { GradeForm } from './GradeForm.jsx';

export default function AssignmentPage() {
  const { assignmentId } = useParams();
  const detail = useLoad(() => api.get(`/workspace/assignments/${assignmentId}`), [assignmentId]);

  if (detail.error) return <p role="alert" className="text-danger">{detail.error}</p>;
  if (!detail.data) return <p className="text-muted" role="status">Loading assignment…</p>;
  const { assignment: a, course, canTeach, submissions, notSubmitted } = detail.data;

  return (
    <div className="space-y-8">
      <div>
        <Link to={`/classroom/${course.offeringId}`} className="text-sm underline">{course.courseCode} {course.courseTitle}</Link>
        <h1 className="mt-1 text-3xl font-bold">{a.title}</h1>
        <p className="text-muted">
          Due {dateTime(a.dueAt)}, {marks(a.maxMarks)} marks{a.acceptLate ? ', late work accepted' : ', no late work'}
        </p>
      </div>

      {a.instructions && <p className="max-w-prose whitespace-pre-wrap">{a.instructions}</p>}
      {a.attachmentName && (
        <DownloadButton path={`/workspace/assignments/${a.id}/attachment`} label={`Download ${a.attachmentName}`} />
      )}

      {!canTeach && <SubmitPanel a={a} onDone={detail.reload} />}

      {canTeach && (
        <section aria-labelledby="subs-heading" className="space-y-3">
          <h2 id="subs-heading" className="text-lg font-bold">Submissions ({submissions.length})</h2>
          {submissions.length === 0 && <p className="text-muted">Nothing submitted yet.</p>}
          <ul className="space-y-3">
            {submissions.map((s) => (
              <li key={`${s.id}-${s.version}`} className="rounded-md border border-line bg-white p-4">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <span>
                    <span className="font-bold">{s.studentName}</span>{' '}
                    <span className="text-sm text-muted">
                      {dateTime(s.submittedAt)}{s.late ? ', late' : ''}{s.version > 1 ? `, version ${s.version}` : ''}
                    </span>
                  </span>
                  <DownloadButton path={`/workspace/submissions/${s.id}/file`} label={`Download ${s.fileName}`} />
                </div>
                <div className="mt-3">
                  <GradeForm submission={s} maxMarks={a.maxMarks} onGraded={detail.reload} />
                </div>
              </li>
            ))}
          </ul>
          {notSubmitted.length > 0 && (
            <p className="text-sm">
              <span className="font-bold">Not submitted ({notSubmitted.length}):</span> {notSubmitted.map((n) => n.fullName).join(', ')}
            </p>
          )}
        </section>
      )}
    </div>
  );
}

function SubmitPanel({ a, onDone }) {
  const [file, setFile] = useState(null);
  const [inputKey, setInputKey] = useState(0);
  const act = useAction();
  const m = a.mine;
  const closed = !a.acceptLate && new Date(a.dueAt) < new Date();

  async function submit(e) {
    e.preventDefault();
    const fd = new FormData();
    fd.append('file', file);
    if (await act.run(() => api.upload(`/workspace/assignments/${a.id}/submission`, fd),
      (r) => (r.late ? 'Submitted (after the deadline, so marked late).' : 'Submitted.'))) {
      setFile(null);
      setInputKey((k) => k + 1);
      onDone();
    }
  }

  return (
    <section aria-labelledby="submit-heading" className="rounded-md border border-line bg-white p-5">
      <h2 id="submit-heading" className="text-lg font-bold">Your submission</h2>
      {m && (
        <div className="mt-2 space-y-1 text-sm">
          <p>
            {m.fileName}, submitted {dateTime(m.submittedAt)}{m.late ? ' (late)' : ''}
            {m.version > 1 ? `, version ${m.version}` : ''}
          </p>
          <DownloadButton path={`/workspace/submissions/${m.id}/file`} label="Download what you submitted" />
          {m.status === 'GRADED' && (
            <p className="pt-2 text-base font-bold text-ok">
              {marks(m.marks)} / {marks(a.maxMarks)}{m.feedback ? `: ${m.feedback}` : ''}
            </p>
          )}
        </div>
      )}
      {!m && <p className="mt-2 text-sm text-muted">You haven't submitted yet.</p>}

      {m?.status !== 'GRADED' && !closed && (
        <form onSubmit={submit} className="mt-4">
          <label htmlFor="sub-file" className="font-bold">{m ? 'Replace with a new file' : 'Choose your file'}</label>
          <input key={inputKey} id="sub-file" type="file" onChange={(e) => setFile(e.target.files?.[0] || null)} className="mt-2 block" required />
          <button type="submit" disabled={act.busy || !file} className={`${primaryButton} mt-3`}>
            {act.busy ? 'Uploading…' : m ? 'Resubmit' : 'Submit'}
          </button>
        </form>
      )}
      {closed && !m && <p className="mt-2 font-bold text-danger">The deadline has passed and late work isn't accepted.</p>}
      <Feedback notice={act.notice} error={act.error} />
    </section>
  );
}
