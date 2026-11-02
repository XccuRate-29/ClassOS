import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client.js';
import { ROLE_LABELS } from '../../auth/roles.js';
import { AddUserForm } from './AddUserForm.jsx';
import { ImportUsers } from './ImportUsers.jsx';

const PAGE_SIZE = 20;

// Spring Data may return paging info at the top level or under "page",
// depending on its serialization mode; accept both.
function pageInfo(data) {
  const p = data.page || data;
  return { totalPages: p.totalPages ?? 1, number: p.number ?? 0, totalElements: p.totalElements ?? 0 };
}

export default function AdminDashboard() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async (p) => {
    setError('');
    try {
      setData(await api.get(`/admin/users?page=${p}&size=${PAGE_SIZE}&sort=createdAt,desc`));
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load(page);
  }, [page, load]);

  function onAdded(user) {
    setNotice(`Added ${user.fullName} as ${ROLE_LABELS[user.role].toLowerCase()}.`);
    if (page === 0) load(0);
    else setPage(0);
  }

  async function setActive(u, active) {
    setNotice('');
    setError('');
    try {
      await api.patch(`/admin/users/${u.id}/active`, { active });
      setNotice(active ? `${u.fullName} can sign in again.` : `${u.fullName} can no longer sign in.`);
      load(page);
    } catch (err) {
      setError(err.message);
    }
  }

  async function resetPhone(u) {
    setNotice('');
    setError('');
    try {
      await api.del(`/admin/attendance/devices/${u.id}`);
      setNotice(`${u.fullName} can now register a new phone the next time they mark attendance.`);
    } catch (err) {
      setError(err.message);
    }
  }

  const info = data ? pageInfo(data) : null;

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold">People</h1>
        <p className="text-muted">Everyone who can sign in to ClassOS, and the role that decides what they see.</p>
      </div>

      <AddUserForm onAdded={onAdded} />
      <ImportUsers onImported={(n) => { setNotice(`Imported ${n} account${n === 1 ? '' : 's'}.`); if (page === 0) load(0); else setPage(0); }} />

      {notice && (
        <p role="status" className="border-l-4 border-ok bg-white px-3 py-2 text-ok">{notice}</p>
      )}

      <section aria-labelledby="users-heading">
        <h2 id="users-heading" className="text-lg font-bold">
          All users {info && <span className="font-normal text-muted">({info.totalElements})</span>}
        </h2>

        {error && <p role="alert" className="mt-3 text-danger">{error}</p>}
        {!data && !error && <p className="mt-3 text-muted" role="status">Loading users…</p>}

        {data && data.content.length === 0 && (
          <p className="mt-3 text-muted">No users yet. Add the first teacher or student above.</p>
        )}

        {data && data.content.length > 0 && (
          <div className="mt-3 overflow-x-auto rounded-md border border-line bg-white">
            <table className="w-full min-w-[52rem] text-left">
              <thead className="border-b border-line text-sm text-muted">
                <tr>
                  <th scope="col" className="px-4 py-2 font-bold">Name</th>
                  <th scope="col" className="px-4 py-2 font-bold">Email</th>
                  <th scope="col" className="px-4 py-2 font-bold">Role</th>
                  <th scope="col" className="px-4 py-2 font-bold">Status</th>
                  <th scope="col" className="px-4 py-2 font-bold">Added</th>
                  <th scope="col" className="px-4 py-2 font-bold"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((u) => (
                  <tr key={u.id} className="border-b border-line last:border-0">
                    <td className="px-4 py-2">{u.fullName}</td>
                    <td className="px-4 py-2">{u.email}</td>
                    <td className="px-4 py-2">{ROLE_LABELS[u.role]}</td>
                    <td className="px-4 py-2">{u.active ? 'Active' : 'Deactivated'}</td>
                    <td className="px-4 py-2 text-muted">
                      {u.createdAt ? new Date(u.createdAt).toLocaleDateString('en-IN', { dateStyle: 'medium' }) : ''}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <button type="button" onClick={() => setActive(u, !u.active)} className="mr-2 rounded border border-line px-3 py-1 text-sm">
                        {u.active ? 'Deactivate' : 'Reactivate'}
                      </button>
                      {u.role === 'STUDENT' && (
                        <button type="button" onClick={() => resetPhone(u)} className="rounded border border-line px-3 py-1 text-sm">
                          Reset phone
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {info && info.totalPages > 1 && (
          <nav aria-label="Pages" className="mt-3 flex items-center gap-4">
            <button type="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}
                    className="rounded border border-line bg-white px-3 py-1.5 disabled:opacity-40">
              Previous
            </button>
            <span className="text-sm text-muted">Page {info.number + 1} of {info.totalPages}</span>
            <button type="button" disabled={page >= info.totalPages - 1} onClick={() => setPage((p) => p + 1)}
                    className="rounded border border-line bg-white px-3 py-1.5 disabled:opacity-40">
              Next
            </button>
          </nav>
        )}
      </section>
    </div>
  );
}
