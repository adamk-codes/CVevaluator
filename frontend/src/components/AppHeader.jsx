import { Link, NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { homeFor } from '../auth/routes'
import { AUTH_IS_ENFORCED } from '../auth/session'
import { Badge } from './ui'

/**
 * Navigation is built from the signed-in role, not from a fixed list with
 * pieces hidden. A candidate's header has no concept of a jobs dashboard rather
 * than a greyed-out link to one.
 */
const NAV = {
  RECRUITER: [
    { to: '/jobs', label: 'Jobs' },
  ],
  CANDIDATE: [
    { to: '/openings', label: 'Open roles' },
    { to: '/applications', label: 'My applications' },
  ],
}

export default function AppHeader() {
  const { user, role, isAuthenticated, signOut } = useAuth()
  const navigate = useNavigate()

  function handleSignOut() {
    signOut()
    navigate('/login', { replace: true })
  }

  return (
    <>
      {!AUTH_IS_ENFORCED && isAuthenticated && (
        <div className="auth-warning-strip">
          Authentication is not enforced — identity is self-asserted and the API accepts any caller.
        </div>
      )}

      <header className="app-header">
        <div className="app-header-inner">
          <Link to={isAuthenticated ? homeFor(role) : '/login'} className="brand">
            CV<span>Evaluator</span>
          </Link>

          {isAuthenticated && (
            <nav className="main-nav">
              {(NAV[role] ?? []).map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
                >
                  {item.label}
                </NavLink>
              ))}
            </nav>
          )}

          <div className="header-spacer" />

          {isAuthenticated ? (
            <div className="row" style={{ gap: 10 }}>
              <span className="account-summary">
                <strong>{user.name}</strong>
                <Badge value={role} className="badge-kind" />
              </span>
              <button className="small" onClick={handleSignOut}>
                Sign out
              </button>
            </div>
          ) : (
            <Link to="/login">
              <button className="small">Sign in</button>
            </Link>
          )}
        </div>
      </header>
    </>
  )
}
