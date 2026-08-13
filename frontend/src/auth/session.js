/**
 * THE AUTH SEAM. Everything in the frontend that depends on how the backend
 * does authentication lives in this file and nowhere else.
 *
 * <h2>How it works</h2>
 *
 * The backend issues a self-signed HS256 JWT from
 * {@code POST /api/auth/login} and {@code POST /api/auth/register}, and every
 * other endpoint is behind the filter chain. The token goes back on each
 * request as {@code Authorization: Bearer <token>}.
 *
 * <p>Nothing outside `src/auth/` reads a token or builds a header — anything
 * needing the current user calls `useAuth()`. That indirection is why swapping
 * the stubbed header identity for real tokens changed this file and no screen.
 *
 * <h2>The token is never decoded here</h2>
 *
 * A JWT's payload is base64, not encrypted, so the name and role could be read
 * out of it client-side without a request. This deliberately does not: the
 * value sits in localStorage where the page's own code can rewrite it, so
 * trusting its claims would mean trusting the client's own storage. The name
 * and role come from the server — with the token on login, and from
 * {@link restore} on reload. The signature is what makes the server's copy
 * authoritative, and only the server checks it.
 */

const STORAGE_KEY = 'cvevaluator.session'

export const RECRUITER = 'RECRUITER'
export const CANDIDATE = 'CANDIDATE'

/**
 * Authentication is now enforced by the backend, so the "not enforced" banner
 * is gone. Kept as a named constant rather than deleted outright because the
 * header and the sign-in screen both read it, and one flag is easier to reason
 * about than two removed conditionals.
 */
export const AUTH_IS_ENFORCED = true

/** Raised for any auth failure. Message is safe to show the user. */
export class AuthError extends Error {
  constructor(message) {
    super(message)
    this.name = 'AuthError'
  }
}

/**
 * The stored session, or null.
 *
 * <p>Shape: `{ user: { id, name, email, role }, token, expiresAt }`.
 *
 * <p>An expired token is treated as no session at all. Without this the app
 * would render a signed-in shell and then take a 401 on the first fetch — the
 * user sees their own name above an error. The backend sends `expiresAt`
 * precisely so the client can get ahead of that.
 */
export function loadSession() {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) return null

  try {
    const session = JSON.parse(raw)
    if (!session?.token || !session?.user?.id || !session?.user?.role) return null

    if (session.expiresAt && new Date(session.expiresAt) <= new Date()) {
      localStorage.removeItem(STORAGE_KEY)
      return null
    }
    return session
  } catch {
    // Hand-edited or written by an older build. Half a session is worse than
    // none - no screen handles a signed-in user with no token.
    return null
  }
}

function persist(body) {
  // Built field by field rather than storing the response whole, so a field the
  // backend adds later does not silently become part of what this app persists.
  const session = {
    user: body.user,
    token: body.token,
    expiresAt: body.expiresAt,
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  return session
}

/**
 * Posts credentials without going through `client.js`.
 *
 * <p>These two endpoints are the only ones reachable with no token, and
 * `client.js` exists to attach one. Routing them through it would mean it
 * imports this file and this file imports it back — a cycle, to share four
 * lines of fetch.
 */
async function postCredentials(path, body, fallbackMessage) {
  let response
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  } catch {
    // fetch rejects only on a transport failure, which here means the API is
    // not running - a different problem from bad credentials and worth saying so.
    throw new AuthError('Could not reach the server. Is the backend running?')
  }

  const text = await response.text()
  const parsed = text ? JSON.parse(text) : null

  if (!response.ok) {
    // GlobalExceptionHandler authors every message and never leaks internals,
    // so it is safe to show as-is. The 401 on login is deliberately the same
    // sentence for a bad password and an unknown email.
    throw new AuthError(parsed?.message ?? fallbackMessage)
  }
  return parsed
}

export async function login(email, password) {
  return persist(
    await postCredentials('/api/auth/login', { email, password }, 'Could not sign in.'),
  )
}

/**
 * @param details `{ name, email, password, role }` — role is chosen by the
 *        registrant, which the backend allows because neither role outranks the
 *        other. Password must be 8-72 characters; the ceiling is BCrypt's.
 */
export async function register({ name, email, password, role }) {
  return persist(
    await postCredentials(
      '/api/auth/register',
      { name, email, password, role },
      'Could not create the account.',
    ),
  )
}

/**
 * Re-reads the current user from the token on a cold page load.
 *
 * <p>Needed because the stored `user` is a copy written at sign-in: a role
 * changed server-side, or an account deleted, would otherwise keep rendering
 * from a snapshot until the token expired. `GET /api/auth/me` is one request
 * and its answer is authoritative.
 *
 * <p>A rejection here means the token is no longer good, so the session is
 * dropped rather than kept — that is the whole point of asking.
 */
export async function restore() {
  const session = loadSession()
  if (!session) return null

  try {
    const response = await fetch('/api/auth/me', { headers: authHeaders() })
    if (!response.ok) {
      logout()
      return null
    }
    return persist({ ...session, user: await response.json() })
  } catch {
    // Transport failure, not a rejected token. The session is kept: signing
    // someone out because the API was briefly unreachable loses their place for
    // a reason that has nothing to do with them.
    return session
  }
}

export function logout() {
  localStorage.removeItem(STORAGE_KEY)
}

/** The header every authenticated request carries. */
export function authHeaders() {
  const session = loadSession()
  return session ? { Authorization: `Bearer ${session.token}` } : {}
}
