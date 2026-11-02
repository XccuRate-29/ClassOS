import { useEffect, useState } from 'react';
import { api } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { MyTimetable } from './timetable/MyTimetable.jsx';

// Dashboard for the three non-admin roles. The timetable is live (Milestone 2);
// the list below names what later milestones add.
const CONTENT = {
  TEACHER: {
    endpoint: '/teacher/home',
    title: 'My classes',
    coming: [],
  },
  STUDENT: {
    endpoint: '/student/home',
    title: 'My week',
    coming: [],
  },
  LAB_ASSISTANT: {
    endpoint: '/lab/home',
    title: 'Labs',
    coming: [['Equipment', 'Units available in each lab and requests from running sessions.']],
  },
};

export default function RoleHome() {
  const { user } = useAuth();
  const content = CONTENT[user.role];
  const [accessError, setAccessError] = useState('');

  useEffect(() => {
    let cancelled = false;
    api.get(content.endpoint).catch((err) => !cancelled && setAccessError(err.message));
    return () => { cancelled = true; };
  }, [content.endpoint]);

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold">{content.title}</h1>
        <p className="text-muted">Signed in as {user.fullName}.</p>
        {accessError && <p role="alert" className="mt-4 text-danger">{accessError}</p>}
      </div>

      <MyTimetable role={user.role} />

      {content.coming.length > 0 && (
      <section aria-labelledby="coming-heading">
        <h2 id="coming-heading" className="text-lg font-bold">Coming next</h2>
        <ul className="mt-2 divide-y divide-line border-y border-line">
          {content.coming.map(([name, desc]) => (
            <li key={name} className="py-3">
              <p className="font-bold">{name}</p>
              <p className="text-muted">{desc}</p>
            </li>
          ))}
        </ul>
      </section>
      )}
    </div>
  );
}
