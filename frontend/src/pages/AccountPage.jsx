import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { ROLE_LABELS } from '../auth/roles.js';
import { Feedback, Field, inputClass, primaryButton, useAction } from './setup/common.jsx';

export default function AccountPage() {
  const { user } = useAuth();
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' });
  const [mismatch, setMismatch] = useState('');
  const act = useAction();
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function submit(e) {
    e.preventDefault();
    if (form.newPassword !== form.confirm) {
      setMismatch("The two new passwords don't match.");
      return;
    }
    setMismatch('');
    const ok = await act.run(() => api.post('/auth/password', {
      currentPassword: form.currentPassword, newPassword: form.newPassword,
    }), 'Password changed. Use the new one next time you sign in.');
    if (ok) setForm({ currentPassword: '', newPassword: '', confirm: '' });
  }

  return (
    <div className="max-w-lg space-y-6">
      <div>
        <h1 className="text-3xl font-bold">Your account</h1>
        <p className="text-muted">{user.fullName}, {user.email}, {ROLE_LABELS[user.role]}</p>
      </div>
      <p>
        Help improve ClassOS: <Link to="/survey" className="font-bold underline">take the two-minute usability survey</Link>.
      </p>
      <form onSubmit={submit} className="space-y-4 rounded-md border border-line bg-white p-5">
        <h2 className="text-lg font-bold">Change password</h2>
        <p className="text-sm text-muted">If you were given a temporary password, change it now.</p>
        <Field id="pw-current" label="Current password">
          <input id="pw-current" type="password" autoComplete="current-password" value={form.currentPassword}
                 onChange={set('currentPassword')} className={inputClass} required />
        </Field>
        <Field id="pw-new" label="New password (at least 10 characters)" error={act.fields.newPassword}>
          <input id="pw-new" type="password" autoComplete="new-password" minLength={10} maxLength={72}
                 value={form.newPassword} onChange={set('newPassword')} className={inputClass} required />
        </Field>
        <Field id="pw-confirm" label="New password again" error={mismatch}>
          <input id="pw-confirm" type="password" autoComplete="new-password" value={form.confirm}
                 onChange={set('confirm')} className={inputClass} required />
        </Field>
        <Feedback notice={act.notice} error={act.error} />
        <button type="submit" disabled={act.busy} className={primaryButton}>Change password</button>
      </form>
    </div>
  );
}
