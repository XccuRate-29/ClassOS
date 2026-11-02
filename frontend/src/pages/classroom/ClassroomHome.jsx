import { Link } from 'react-router-dom';
import { api } from '../../api/client.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { useLoad } from '../setup/common.jsx';

export default function ClassroomHome() {
  const { user } = useAuth();
  const courses = useLoad(() => api.get('/workspace/courses'), []);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold">Classroom</h1>
        <p className="text-muted">Notes, assignments and submissions for each of your courses this term.</p>
      </div>
      {courses.error && <p role="alert" className="text-danger">{courses.error}</p>}
      {courses.data?.length === 0 && (
        <p className="text-muted">
          {user.role === 'STUDENT' ? "You aren't enrolled in any courses this term yet." : 'You have no courses this term.'}
        </p>
      )}
      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {courses.data?.map((c) => (
          <li key={c.offeringId}>
            <Link to={`/classroom/${c.offeringId}`}
                  className="block h-full rounded-md border border-line bg-white p-4 hover:border-board">
              <span className="block font-bold">{c.courseCode} {c.courseTitle}</span>
              <span className="block text-sm text-muted">{c.sectionName}, {c.teacherName}</span>
              <span className="mt-3 block text-sm">
                {c.materials} material{c.materials === 1 ? '' : 's'}, {c.assignments} assignment{c.assignments === 1 ? '' : 's'}
              </span>
              {c.toGrade > 0 && <span className="mt-1 block text-sm font-bold">{c.toGrade} to grade</span>}
              {c.pending > 0 && <span className="mt-1 block text-sm font-bold">{c.pending} to submit</span>}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
