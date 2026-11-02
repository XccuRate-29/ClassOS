import { useState } from 'react';
import { api } from '../../api/client.js';
import { ROLE_LABELS } from '../../auth/roles.js';

const EMPTY = { fullName: '', email: '', role: 'STUDENT', password: '' };

export function AddUserForm({ onAdded }) {
  const [form, setForm] = useState(EMPTY);
  const [fieldErrors, setFieldErrors] = useState({});
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));

  async function onSubmit(e) {
    e.preventDefault();
    setError('');
    // Mirror the server's rule for quick feedback; the server still validates.
    if (form.password.length < 10) {
      setFieldErrors({ password: 'Use at least 10 characters.' });
      return;
    }
    setFieldErrors({});
    setSaving(true);
    try {
      const created = await api.post('/admin/users', form);
      setForm(EMPTY);
      onAdded(created);
    } catch (err) {
      setFieldErrors(err.fields || {});
      setError(err.status === 409 ? 'Someone already uses this email.' : err.message);
    } finally {
      setSaving(false);
    }
  }

  const input = 'mt-1 w-full rounded border border-line bg-white px-3 py-2';
  const fieldError = (key) =>
    fieldErrors[key] && (
      <p id={`${key}-error`} className="mt-1 text-sm text-danger">{fieldErrors[key]}</p>
    );

  return (
    <form onSubmit={onSubmit} className="rounded-md border border-line bg-white p-5" noValidate>
      <h2 className="text-lg font-bold">Add a user</h2>
      <p className="text-sm text-muted">Share the temporary password with them directly, not by group chat.</p>

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <div>
          <label htmlFor="fullName" className="font-bold">Full name</label>
          <input id="fullName" value={form.fullName} onChange={set('fullName')} className={input} required
                 aria-describedby={fieldErrors.fullName ? 'fullName-error' : undefined} />
          {fieldError('fullName')}
        </div>
        <div>
          <label htmlFor="newEmail" className="font-bold">Email</label>
          <input id="newEmail" type="email" value={form.email} onChange={set('email')} className={input} required
                 aria-describedby={fieldErrors.email ? 'email-error' : undefined} />
          {fieldError('email')}
        </div>
        <div>
          <label htmlFor="role" className="font-bold">Role</label>
          <select id="role" value={form.role} onChange={set('role')} className={input}>
            {Object.entries(ROLE_LABELS).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="newPassword" className="font-bold">Temporary password</label>
          <input id="newPassword" type="text" autoComplete="off" value={form.password} onChange={set('password')}
                 className={input} required aria-describedby={fieldErrors.password ? 'password-error' : undefined} />
          {fieldError('password')}
        </div>
      </div>

      {error && <p role="alert" className="mt-4 text-danger">{error}</p>}

      <button
        type="submit"
        disabled={saving}
        className="mt-5 rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep disabled:opacity-50"
      >
        {saving ? 'Adding…' : 'Add user'}
      </button>
    </form>
  );
}
