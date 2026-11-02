import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';
import { homeFor } from './roles.js';

// Hides pages from the wrong roles. This is a convenience, not security:
// the backend independently rejects every request the role isn't allowed to make.
export function RequireRole({ roles, children }) {
  const { user, status } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return <p className="p-8 text-muted" role="status">Loading your account…</p>;
  }
  if (!user) {
    // Keep the query string: a scanned attendance QR code carries the session and code in it.
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  if (!roles.includes(user.role)) {
    return <Navigate to={homeFor(user.role)} replace />;
  }
  return children;
}
