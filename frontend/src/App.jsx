import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './components/AppShell.jsx';
import { RequireRole } from './auth/RequireRole.jsx';
import { useAuth } from './auth/AuthContext.jsx';
import { homeFor } from './auth/roles.js';
import LoginPage from './pages/LoginPage.jsx';
import AdminDashboard from './pages/admin/AdminDashboard.jsx';
import RoleHome from './pages/RoleHome.jsx';
import AdminTimetablePage from './pages/timetable/AdminTimetablePage.jsx';
import BookingsPage from './pages/bookings/BookingsPage.jsx';
import TeacherAttendancePage from './pages/attendance/TeacherAttendancePage.jsx';
import StudentAttendancePage from './pages/attendance/StudentAttendancePage.jsx';
import AttendLanding from './pages/attendance/AttendLanding.jsx';
import SetupPage from './pages/setup/SetupPage.jsx';
import AccountPage from './pages/AccountPage.jsx';
import ClassroomHome from './pages/classroom/ClassroomHome.jsx';
import CoursePage from './pages/classroom/CoursePage.jsx';
import AssignmentPage from './pages/classroom/AssignmentPage.jsx';
import GradingQueuePage from './pages/grading/GradingQueuePage.jsx';
import SurveyPage from './pages/SurveyPage.jsx';
import FeedbackPage from './pages/admin/FeedbackPage.jsx';

function Protected({ roles, children }) {
  return (
    <RequireRole roles={roles}>
      <AppShell>{children}</AppShell>
    </RequireRole>
  );
}

function Home() {
  const { user, status } = useAuth();
  if (status === 'loading') return null;
  return <Navigate to={user ? homeFor(user.role) : '/login'} replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/admin" element={<Protected roles={['ADMIN']}><AdminDashboard /></Protected>} />
      <Route path="/admin/setup" element={<Protected roles={['ADMIN']}><SetupPage /></Protected>} />
      <Route path="/account" element={<Protected roles={['ADMIN', 'TEACHER', 'STUDENT', 'LAB_ASSISTANT']}><AccountPage /></Protected>} />
      <Route path="/classroom" element={<Protected roles={['TEACHER', 'STUDENT', 'ADMIN']}><ClassroomHome /></Protected>} />
      <Route path="/classroom/:offeringId" element={<Protected roles={['TEACHER', 'STUDENT', 'ADMIN']}><CoursePage /></Protected>} />
      <Route path="/classroom/assignments/:assignmentId" element={<Protected roles={['TEACHER', 'STUDENT', 'ADMIN']}><AssignmentPage /></Protected>} />
      <Route path="/teacher/grading" element={<Protected roles={['TEACHER']}><GradingQueuePage /></Protected>} />
      <Route path="/survey" element={<Protected roles={['ADMIN', 'TEACHER', 'STUDENT', 'LAB_ASSISTANT']}><SurveyPage /></Protected>} />
      <Route path="/admin/feedback" element={<Protected roles={['ADMIN']}><FeedbackPage /></Protected>} />
      <Route path="/admin/timetable" element={<Protected roles={['ADMIN']}><AdminTimetablePage /></Protected>} />
      <Route path="/bookings" element={<Protected roles={['TEACHER', 'LAB_ASSISTANT', 'ADMIN']}><BookingsPage /></Protected>} />
      <Route path="/teacher/attendance" element={<Protected roles={['TEACHER']}><TeacherAttendancePage /></Protected>} />
      <Route path="/student/attendance" element={<Protected roles={['STUDENT']}><StudentAttendancePage /></Protected>} />
      <Route path="/attend" element={<Protected roles={['STUDENT']}><AttendLanding /></Protected>} />
      <Route path="/teacher" element={<Protected roles={['TEACHER']}><RoleHome /></Protected>} />
      <Route path="/student" element={<Protected roles={['STUDENT']}><RoleHome /></Protected>} />
      <Route path="/lab" element={<Protected roles={['LAB_ASSISTANT']}><RoleHome /></Protected>} />
      <Route path="*" element={<Home />} />
    </Routes>
  );
}
