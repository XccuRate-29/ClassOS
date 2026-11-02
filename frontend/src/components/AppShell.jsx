import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { ROLE_LABELS } from '../auth/roles.js';
import { FeedbackButton } from './FeedbackButton.jsx';

const NAV = {
  ADMIN: [
    { to: '/admin', label: 'People', end: true },
    { to: '/admin/setup', label: 'Setup' },
    { to: '/admin/timetable', label: 'Timetable' },
    { to: '/bookings', label: 'Room bookings' },
    { to: '/admin/feedback', label: 'Feedback results' },
  ],
  TEACHER: [
    { to: '/teacher', label: 'My classes', end: true },
    { to: '/classroom', label: 'Classroom' },
    { to: '/teacher/grading', label: 'Grading' },
    { to: '/teacher/attendance', label: 'Attendance' },
    { to: '/bookings', label: 'Room bookings' },
  ],
  STUDENT: [
    { to: '/student', label: 'My week', end: true },
    { to: '/classroom', label: 'Classroom' },
    { to: '/student/attendance', label: 'Attendance' },
  ],
  LAB_ASSISTANT: [{ to: '/lab', label: 'Labs', end: true }, { to: '/bookings', label: 'Room bookings' }],
};

export function AppShell({ children }) {
  const { user, logout } = useAuth();
  const links = NAV[user.role] || [];

  return (
    <div className="min-h-screen">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-10 focus:bg-highlight focus:px-3 focus:py-2 focus:text-ink"
      >
        Skip to content
      </a>
      <header className="bg-board text-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-8 gap-y-3 px-4 py-3 sm:px-6">
          <span className="text-xl font-bold tracking-tight">ClassOS</span>
          <nav aria-label="Main" className="flex gap-6">
            {links.map((l) => (
              <NavLink
                key={l.to}
                to={l.to}
                end={l.end}
                className={({ isActive }) =>
                  `border-b-4 py-1 ${isActive ? 'border-highlight' : 'border-transparent hover:border-white/40'}`
                }
              >
                {l.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-4 text-sm">
            <FeedbackButton />
            <NavLink to="/account" className="underline-offset-4 hover:underline">
              {user.fullName} <span className="text-white/70">({ROLE_LABELS[user.role]})</span>
            </NavLink>
            <button
              type="button"
              onClick={logout}
              className="rounded border border-white/40 px-3 py-1.5 hover:bg-board-deep"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main id="main" className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        {children}
      </main>
    </div>
  );
}
