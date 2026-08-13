import { useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { homeFor } from '../auth/routes'
import { CANDIDATE, RECRUITER } from '../auth/session'

/**
 * The registration form, in its final shape, against a backend that cannot yet
 * accept it.
 *
 * <p>Submitting reports the honest reason rather than creating a local-only
 * account. A fake account would let someone register, apply for a job, and have
 * every request attributed to a user id the server has never seen — surfacing
 * much later as "Current user not found" from deep inside a controller. Failing
 * here, with a sentence, is the better failure.
 */
export default function RegisterPage() {
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
      await signUp(form)
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
              required
            />
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
