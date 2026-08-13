/**
 * THE AUTH SEAM. Everything in the frontend that depends on how the backend
 * does authentication lives in this file and nowhere else.
 *
 * <h2>Read this before changing anything auth-related</h2>
 *
 * The backend has no authentication yet — no login endpoint, no register
 * endpoint, no token, no `/me`. `HeaderCurrentUserProvider` reads an
 * `X-User-Id` header and trusts it. That is a deliberate scope decision on the
 * backend side, and it is being replaced in a separate piece of work.
 *
 * Rather than wait for it, every screen, route, guard and hook in this app is
 * written against the {@link loadSession}/{@link login}/{@link authHeaders}
 * contract below, which is designed to be the same before and after real auth
 * exists. When the backend lands, the changes are confined to the four
 * functions marked SWAP in this file. Nothing outside `src/auth/` should ever
 * read a token, build an auth header, or know what a session is made of — if
 * something needs the current user it calls `useAuth()`.
 *
 * <h2>What is provisional, precisely</h2>
 *
 * <ul>
 *   <li><strong>Passwords are not checked.</strong> There is nothing to check
 *       them against. {@link login} resolves an email to one of the seeded
 *       users and ignores the password entirely.
 *   <li><strong>Identity is self-asserted.</strong> The session is held in
 *       localStorage and the only thing sent to the server is the user id, so
 *       a user can trivially become another user. This is not a frontend
 *       weakness to be fixed in the frontend — the server currently accepts
 *       whatever `X-User-Id` it is given, so no amount of client code makes it
 *       a boundary.
 *   <li><strong>Registration cannot work at all.</strong> There is no endpoint
 *       that creates a user, so {@link register} refuses rather than pretending.
 * </ul>
 *
 * Because of the second point the app shows a standing banner saying auth is
 * not enforced. Do not remove it before the backend does enforce it.
 */

const STORAGE_KEY = 'cvevaluator.session'

export const RECRUITER = 'RECRUITER'
export const CANDIDATE = 'CANDIDATE'

/**
 * SWAP — delete this entirely once a login endpoint exists.
 *
 * Stands in for the user lookup the server will do. These are the rows in
 * `users`; the ids are what `X-User-Id` has to carry for the existing
 * controllers to attribute a job or a submission correctly, which is why they
 * are real ids and not invented ones.
 */
const SEEDED_USERS = [
  { id: 1, name: 'Adam', email: 'adam.kh@gmail.com', role: RECRUITER },
  { id: 15, name: 'Recruiter', email: 'recruiter@apliman.com', role: RECRUITER },
  { id: 6, name: 'Test Candidate', email: 'candidate@example.com', role: CANDIDATE },
]

/** Offered on the sign-in screen so nobody has to guess a seeded address. */
export const knownAccounts = () => SEEDED_USERS

/**
 * Raised for any auth failure. Carries a message safe to show the user.
 * Kept distinct from ApiError so a failed sign-in is never confused with a
 * failed data fetch.
 */
export class AuthError extends Error {
  constructor(message) {
    super(message)
    this.name = 'AuthError'
  }
}

/**
 * True while identity is self-asserted rather than proven.
 *
 * Drives the "not enforced" banner. SWAP this to `false` in the same change
 * that makes {@link login} call a real endpoint — it is a single constant so
 * the banner cannot be left behind by accident, or removed too early.
 */
export const AUTH_IS_ENFORCED = false

/**
 * The stored session, or null.
 *
 * <p>Shape: `{ user: { id, name, email, role }, token }`. `token` is null
 * today. It is in the shape now so that adding one later is not a change to
 * the shape every consumer reads.
 */
export function loadSession() {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    const session = JSON.parse(raw)
    // A stored session from an older build, or hand-edited rubbish, must not
    // put the app in a half-signed-in state that no screen handles.
    return session?.user?.id && session?.user?.role ? session : null
  } catch {
    return null
  }
}

function persist(session) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  return session
}

/**
 * SWAP — becomes `POST /api/auth/login`.
 *
 * The replacement should keep this signature and keep returning the same
 * session shape; then no caller changes. Roughly:
 *
 *   const body = await request('/api/auth/login', {method:'POST', body:{email,password}})
 *   return persist({ user: body.user, token: body.token })
 *
 * @throws AuthError when the email is not recognised
 */
export async function login(email, password) {
  const match = SEEDED_USERS.find(
    (u) => u.email.toLowerCase() === String(email).trim().toLowerCase(),
  )
  if (!match) {
    throw new AuthError(
      'No account with that email. Authentication is not wired up yet, so only the seeded accounts below can sign in.',
    )
  }
  // The password is deliberately unused. Saying so out loud here because a
  // silent unused parameter is how this gets mistaken for a real check.
  void password
  return persist({ user: match, token: null })
}

/**
 * SWAP — becomes `POST /api/auth/register`.
 *
 * Refuses rather than faking it. Creating a local-only account would let
 * someone sign up, submit a CV, and have every request attributed to a user id
 * the server has never heard of — which fails deep in the controller with
 * "Current user not found" rather than here with a sentence.
 */
export async function register() {
  throw new AuthError(
    'Registration needs a backend endpoint that does not exist yet. Sign in with one of the seeded accounts for now.',
  )
}

export function logout() {
  localStorage.removeItem(STORAGE_KEY)
}

/**
 * SWAP — the headers every API request carries.
 *
 * Today: the stub header `HeaderCurrentUserProvider` reads. Later: almost
 * certainly `Authorization: Bearer <token>`, at which point this becomes
 *
 *   return session?.token ? { Authorization: `Bearer ${session.token}` } : {}
 *
 * `client.js` is the only consumer and it does not care which.
 */
export function authHeaders() {
  const session = loadSession()
  return session ? { 'X-User-Id': String(session.user.id) } : {}
}
