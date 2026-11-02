import { useEffect, useState } from 'react';
import { api } from '../../api/client.js';
import { TimetableGrid } from '../../components/TimetableGrid.jsx';

const DESCRIBE = {
  TEACHER: (s) => [s.courseCode, s.sectionName, s.roomCode],
  STUDENT: (s) => [s.courseCode, s.teacherName, s.roomCode],
  LAB_ASSISTANT: (s) => [s.roomCode, s.courseCode, s.sectionName],
  ADMIN: (s) => [s.courseCode, s.sectionName, s.roomCode],
};

const EMPTY = {
  TEACHER: "You don't have any classes in the timetable yet.",
  STUDENT: "You're not in a section's timetable yet. Ask your department office to enrol you.",
  LAB_ASSISTANT: 'No lab sessions are scheduled yet.',
  ADMIN: 'No classes are scheduled yet.',
};

export function MyTimetable({ role }) {
  const [view, setView] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    api.get('/timetable/me')
      .then((v) => !cancelled && setView(v))
      .catch((err) => !cancelled && setError(err.message));
    return () => { cancelled = true; };
  }, []);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  if (!view) return <p className="text-muted" role="status">Loading timetable…</p>;

  return (
    <section aria-labelledby="my-tt-heading">
      <h2 id="my-tt-heading" className="text-lg font-bold">This week, {view.term.name}</h2>
      {view.slots.length === 0 ? (
        <p className="mt-2 text-muted">{EMPTY[role]}</p>
      ) : (
        <div className="mt-3">
          <TimetableGrid periods={view.periods} workingDays={view.term.workingDays}
                         slots={view.slots} describe={DESCRIBE[role]} />
        </div>
      )}
    </section>
  );
}
