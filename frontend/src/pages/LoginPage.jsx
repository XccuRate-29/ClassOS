import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { homeFor } from '../auth/roles.js';

export default function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  if (user) return <Navigate to={homeFor(user.role)} replace />;

  async function onSubmit(e) {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      const me = await login(email, password);
      // Return to the page the user originally asked for, if their role allows it;
      // RequireRole redirects them home otherwise.
      navigate(location.state?.from || homeFor(me.role), { replace: true });
    } catch (err) {
      setError(err.status === 401 ? 'Email or password is incorrect.' : err.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="grid min-h-screen md:grid-cols-2">
      <section className="timetable-grid flex flex-col justify-end bg-board px-8 py-10 text-white md:px-14 md:py-16">
        <h1 className="text-5xl font-bold tracking-tight md:text-7xl">
          <span className="highlighter">ClassOS</span>
        </h1>
        <p className="mt-4 max-w-sm text-lg text-white/85">
          Timetables, rooms, attendance and assignments for your department, in one place.
        </p>
      </section>

      <section className="flex items-center px-8 py-12 md:px-14">
        <form onSubmit={onSubmit} className="w-full max-w-sm" noValidate>
          <h2 className="text-2xl font-bold">Sign in</h2>
          <p className="mt-1 text-muted">Use the email your department registered for you.</p>

          <label htmlFor="email" className="mt-8 block font-bold">Email</label>
          <input
            id="email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="mt-1 w-full rounded border border-line bg-white px-3 py-2"
          />

          <label htmlFor="password" className="mt-5 block font-bold">Password</label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="mt-1 w-full rounded border border-line bg-white px-3 py-2"
          />

          {error && (
            <p role="alert" className="mt-5 rounded border-l-4 border-danger bg-white px-3 py-2 text-danger">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={submitting || !email || !password}
            className="mt-8 w-full rounded bg-board px-4 py-2.5 font-bold text-white hover:bg-board-deep disabled:opacity-50"
          >
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </section>
    </div>
  );
}
