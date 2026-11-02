import { useState } from 'react';
import { api } from '../../api/client.js';
import { marks as fmt } from '../../lib/format.js';

/**
 * Sends the submission version the teacher is looking at. If the student resubmitted
 * meanwhile, the server refuses (409) and the teacher is asked to download the new file.
 */
export function GradeForm({ submission, maxMarks, onGraded }) {
  const [value, setValue] = useState(fmt(submission.marks));
  const [feedback, setFeedback] = useState(submission.feedback || '');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  async function save(e) {
    e.preventDefault();
    setSaving(true);
    setError('');
    try {
      const row = await api.post(`/workspace/submissions/${submission.id}/grade`,
        { marks: Number(value), feedback, version: submission.version });
      onGraded(row);
    } catch (err) {
      setError(err.status === 409 && err.data?.currentVersion
        ? 'The student uploaded a new version while you were grading. Download it again before grading.'
        : err.message);
      if (err.status === 409) onGraded(null);  // ask the parent to reload
    } finally {
      setSaving(false);
    }
  }

  const id = `grade-${submission.id}`;
  return (
    <form onSubmit={save} className="flex flex-wrap items-end gap-3">
      <div>
        <label htmlFor={`${id}-marks`} className="block text-sm font-bold">Marks (out of {fmt(maxMarks)})</label>
        <input id={`${id}-marks`} type="number" min="0" max={maxMarks} step="0.5" value={value}
               onChange={(e) => setValue(e.target.value)} required
               className="mt-1 w-28 rounded border border-line bg-white px-2 py-1.5" />
      </div>
      <div className="min-w-56 flex-1">
        <label htmlFor={`${id}-fb`} className="block text-sm font-bold">Feedback</label>
        <input id={`${id}-fb`} value={feedback} onChange={(e) => setFeedback(e.target.value)} maxLength={5000}
               className="mt-1 w-full rounded border border-line bg-white px-2 py-1.5" />
      </div>
      <button type="submit" disabled={saving || value === ''}
              className="rounded bg-board px-4 py-1.5 font-bold text-white hover:bg-board-deep disabled:opacity-50">
        {saving ? 'Saving…' : submission.status === 'GRADED' ? 'Update grade' : 'Save grade'}
      </button>
      {error && <p role="alert" className="w-full text-sm text-danger">{error}</p>}
    </form>
  );
}
