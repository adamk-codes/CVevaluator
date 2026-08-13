import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { homeFor } from '../auth/routes'
import { CANDIDATE, RECRUITER } from '../auth/session'

/**
 * Registration.
 *
 * <p>The backend returns a token alongside the new account, so a successful
 * registration is also a sign-in and lands the user on their own dashboard
 * rather than bouncing them to the login screen to retype what they just typed.
 */
export default function RegisterPage() {
  const navigate = useNavigate()
  const { isAuthenticated, role, signUp } = useAuth()

  const [form, setForm] = useState({
    name: '',
    email: '',
    password: '',
    role: CANDIDATE,
  })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  if (isAuthenticated) return <Navigate to={homeFor(role)} replace />

  const set = (patch) => setForm((current) => ({ ...current, ...patch }))

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const created = await signUp(form)
      navigate(homeFor(created?.user?.role), { replace: true })
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="page page-narrow">
      <div className="auth-card">
        <h1>Create an account</h1>
        <p className="subtitle">Choose how you will use the platform.</p>

        <form onSubmit={submit} style={{ marginTop: 20 }}>
          <div className="field">
            <label htmlFor="name">Full name</label>
            <input id="name" value={form.name} onChange={(e) => set({ name: e.target.value })} required />
          </div>

          <div className="field">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              value={form.email}
              onChange={(e) => set({ email: e.target.value })}
              autoComplete="username"
              required
            />
          </div>

          <div className="field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              value={form.password}
              onChange={(e) => set({ password: e.target.value })}
              autoComplete="new-password"
              minLength={8}
              maxLength={72}
              required
            />
            {/* Both bounds mirror RegisterRequest. The ceiling is BCrypt's: it
                hashes the first 72 bytes and ignores the rest, so the backend
                rejects rather than silently truncating. */}
            <p className="hint">8-72 characters.</p>
          </div>

          <div className="field">
            <label>I am a…</label>
            <div className="role-choice">
              {[
                { value: CANDIDATE, title: 'Candidate', blurb: 'Browse openings and submit a CV.' },
                { value: RECRUITER, title: 'Recruiter', blurb: 'Post roles and review applicants.' },
              ].map((option) => (
                <button
                  type="button"
                  key={option.value}
                  className={`role-option ${form.role === option.value ? 'selected' : ''}`}
                  onClick={() => set({ role: option.value })}
                  aria-pressed={form.role === option.value}
                >
                  <strong>{option.title}</strong>
                  <span className="hint" style={{ marginTop: 3 }}>{option.blurb}</span>
                </button>
              ))}
            </div>
          </div>

          {error && <div className="alert alert-error" style={{ marginBottom: 14 }}>{error}</div>}

          <button type="submit" className="primary" disabled={busy} style={{ width: '100%' }}>
            {busy ? 'Creating…' : 'Create account'}
          </button>
        </form>

        <p className="hint" style={{ textAlign: 'center', marginTop: 16 }}>
          Already have one? <Link to="/login">Sign in</Link>
        </p>
      </div>
    </main>
  )
}
