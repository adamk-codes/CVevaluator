import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'
import { homeFor } from './routes'

/**
 * Route guard. Wraps a group of routes and lets them render only for a signed-in
 * user of the right role.
 *
 * <h2>This is navigation, not security</h2>
 *
 * It decides which screens a user is shown. It does not, and cannot, decide
 * what data they are allowed to fetch — the server does that, and at the moment
 * the server does not do it at all. Anyone can call any endpoint directly with
 * any `X-User-Id`. Treat this as routing that keeps an honest user out of the
 * wrong workflow, and put the real check in the backend.
 *
 * @param role optional. When set, the signed-in user must hold it.
 */
export default function RequireAuth({ role }) {
  const { isAuthenticated, role: currentRole } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    // `state.from` so signing in returns the user where they were headed
    // instead of dumping them on a landing page. `replace` keeps the guarded
    // URL out of history, so Back from the sign-in screen does not bounce
    // through it again.
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  // Signed in but wrong persona: send them to their own home rather than
  // showing a dead end. A candidate who follows a recruiter link should land
  // somewhere usable.
  if (role && currentRole !== role) {
    return <Navigate to={homeFor(currentRole)} replace />
  }

  return <Outlet />
}
