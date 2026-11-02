import { useState } from 'react';
import { api } from '../../api/client.js';
import { DownloadButton } from '../../components/DownloadButton.jsx';
import { ROLE_LABELS } from '../../auth/roles.js';
import { dateTime } from '../../lib/format.js';
import { useLoad } from '../setup/common.jsx';

const CATEGORY = { BUG: 'Problem', IDEA: 'Idea', PRAISE: 'Works well', OTHER: 'Other' };
const SHORT = ['Use frequently', 'Unnecessarily complex', 'Easy to use', 'Need technical help', 'Well integrated',
  'Inconsistent', 'Quick to learn', 'Cumbersome', 'Confident', 'Lot to learn first'];

export default function FeedbackPage() {
  const [status, setStatus] = useState('NEW');
  const [category, setCategory] = useState('');
  const sus = useLoad(() => api.get('/admin/feedback/sus'), []);
  const list = useLoad(() => api.get(`/admin/feedback?status=${status}&category=${category}`), [status, category]);
  const [error, setError] = useState('');

  async function mark(id, next) {
    try {
      await api.patch(`/admin/feedback/${id}`, { status: next });
      list.reload();
    } catch (err) {
      setError(err.message);
    }
  }

  const s = sus.data;
  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold">Feedback</h1>
        <p className="text-muted">What people told you, and the usability survey results, for the Milestone 5 evaluation.</p>
      </div>

      <section aria-labelledby="sus-heading" className="rounded-md border border-line bg-white p-5">
        <h2 id="sus-heading" className="text-lg font-bold">System Usability Scale</h2>
        {sus.error && <p role="alert" className="text-danger">{sus.error}</p>}
        {s && s.responses === 0 && <p className="mt-2 text-muted">No survey responses yet. Share the link: /survey</p>}
        {s && s.responses > 0 && (
          <div className="mt-3 space-y-4">
            <p>
              <span className="text-4xl font-bold">{s.averageScore}</span>
              <span className="ml-2 text-muted">average from {s.responses} response{s.responses === 1 ? '' : 's'} (0–100)</span>
            </p>
            <p className="text-sm text-muted">
              A commonly cited average across many products is about 68. With few responses, treat the number as a rough signal.
            </p>
            {s.byRole.length > 0 && (
              <p className="text-sm">{s.byRole.map((r) => `${ROLE_LABELS[r.role] || r.role}: ${r.averageScore} (${r.responses})`).join(' · ')}</p>
            )}
            <div className="overflow-x-auto">
              <table className="w-full min-w-[30rem] text-left text-sm">
                <caption className="text-left text-sm text-muted">Average answer per statement, 1 = strongly disagree, 5 = strongly agree. For even statements, lower is better.</caption>
                <thead className="border-b border-line text-muted">
                  <tr><th scope="col" className="py-1 pr-4 font-bold">Statement</th><th scope="col" className="py-1 font-bold">Average</th></tr>
                </thead>
                <tbody>
                  {s.perItem.map((avg, i) => (
                    <tr key={i} className="border-b border-line last:border-0">
                      <td className="py-1 pr-4">{i + 1}. {SHORT[i]} <span className="text-muted">({i % 2 === 0 ? 'higher is better' : 'lower is better'})</span></td>
                      <td className="py-1 tabular-nums">{avg.toFixed(2)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>

      <section aria-labelledby="fb-heading" className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <h2 id="fb-heading" className="text-lg font-bold">Comments</h2>
          <DownloadButton path="/admin/feedback/export" label="Export all as CSV" />
        </div>
        <div className="flex flex-wrap gap-4">
          <div>
            <label htmlFor="f-status" className="block text-sm font-bold">Status</label>
            <select id="f-status" value={status} onChange={(e) => setStatus(e.target.value)} className="mt-1 rounded border border-line bg-white px-2 py-1.5">
              <option value="">All</option><option value="NEW">New</option><option value="REVIEWED">Reviewed</option><option value="DONE">Done</option>
            </select>
          </div>
          <div>
            <label htmlFor="f-cat" className="block text-sm font-bold">Type</label>
            <select id="f-cat" value={category} onChange={(e) => setCategory(e.target.value)} className="mt-1 rounded border border-line bg-white px-2 py-1.5">
              <option value="">All</option>
              {Object.entries(CATEGORY).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
        </div>
        {(error || list.error) && <p role="alert" className="text-danger">{error || list.error}</p>}
        {list.data?.length === 0 && <p className="text-muted">Nothing here.</p>}
        <ul className="space-y-3">
          {list.data?.map((f) => (
            <li key={f.id} className="rounded-md border border-line bg-white p-4">
              <p className="text-sm text-muted">
                {CATEGORY[f.category]}{f.rating ? `, rated ${f.rating}/5` : ''} · {f.userName} ({ROLE_LABELS[f.role] || f.role})
                {f.page ? ` on ${f.page}` : ''} · {dateTime(f.createdAt)}
              </p>
              <p className="mt-1 whitespace-pre-wrap">{f.message}</p>
              <div className="mt-3 flex gap-2">
                {f.status !== 'REVIEWED' && <button type="button" onClick={() => mark(f.id, 'REVIEWED')} className="rounded border border-line px-3 py-1 text-sm">Mark reviewed</button>}
                {f.status !== 'DONE' && <button type="button" onClick={() => mark(f.id, 'DONE')} className="rounded border border-line px-3 py-1 text-sm">Mark done</button>}
              </div>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
