import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { homeFor } from '../auth/routes'

export default function LoginPage() {
  const { isAuthenticated, role, signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  if (isAuthenticated) return <Navigate to={homeFor(role)} replace />

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const signedIn = await signIn(email, password)
      // Back to wherever the guard interrupted them, else their own home.
      const intended = location.state?.from?.pathname
      navigate(intended ?? homeFor(signedIn?.user?.role), { replace: true })
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="page page-narrow">
      <div className="auth-card">
        <h1>Sign in</h1>
        <p className="subtitle">Recruiters post roles. Candidates apply to them.</p>

        <form onSubmit={submit} style={{ marginTop: 20 }}>
          <div className="field">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              required
            />
          </div>

          <div className="field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </div>

          {error && <div className="alert alert-error" style={{ marginBottom: 14 }}>{error}</div>}

          <button type="submit" className="primary" disabled={busy} style={{ width: '100%' }}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="hint" style={{ textAlign: 'center', marginTop: 16 }}>
          No account? <Link to="/register">Register</Link>
        </p>
      </div>
    </main>
  )
}
