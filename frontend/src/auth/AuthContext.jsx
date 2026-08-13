import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import * as session from './session'

/**
 * The only way the rest of the app asks who is signed in.
 *
 * Nothing outside `src/auth/` imports `session.js` directly — that indirection
 * is what kept the backend auth contract from leaking into screens, and is why
 * replacing the stub with real JWTs changed session.js and this file only.
 */
const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const queryClient = useQueryClient()

  // Seeded synchronously from storage so a reload renders the signed-in shell
  // immediately instead of flashing the sign-in screen while /me is in flight.
  const [current, setCurrent] = useState(session.loadSession)

  /**
   * The cache is cleared on every identity change, in both directions.
   *
   * Without it a recruiter's job list stays in the cache after signing out and
   * is served instantly to whoever signs in next — the data is scoped to a
   * user, so the cache has to be too.
   */
  const reset = useCallback(
    (next) => {
      queryClient.clear()
      setCurrent(next)
      // Returned so a caller can route on the new role in the same tick. Reading
      // `role` off the context instead would give the value from before this
      // render, and send a candidate to the recruiter's home on first sign-in.
      return next
    },
    [queryClient],
  )

  /**
   * Confirm the stored token still works, once per page load.
   *
   * The optimistic render above is a copy of what was true at sign-in. This
   * replaces it with the server's answer, and drops the session entirely if the
   * token has been revoked or the account is gone — otherwise a stale token
   * renders a working-looking app whose every request 401s.
   *
   * Guarded by `active` because in StrictMode the effect runs twice in
   * development; without it the second pass can apply a result after the first
   * has already signed the user out.
   */
  useEffect(() => {
    if (!session.loadSession()) return undefined

    let active = true
    session.restore().then((restored) => {
      if (!active) return
      setCurrent(restored)
    })
    return () => {
      active = false
    }
  }, [])

  const value = useMemo(
    () => ({
      user: current?.user ?? null,
      role: current?.user?.role ?? null,
      isAuthenticated: Boolean(current),
      isRecruiter: current?.user?.role === session.RECRUITER,
      isCandidate: current?.user?.role === session.CANDIDATE,

      signIn: async (email, password) => reset(await session.login(email, password)),
      signUp: async (details) => reset(await session.register(details)),
      signOut: () => {
        session.logout()
        reset(null)
      },
    }),
    [current, reset],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return context
}
