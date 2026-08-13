import { CANDIDATE, RECRUITER } from './session'

/**
 * Where each persona lands after signing in, and where a misrouted user is sent.
 *
 * One function rather than the conditional repeated at each redirect, because
 * there are four of them (guard, sign-in success, root redirect, brand link)
 * and a role added later must not be able to reach three of them and miss one.
 */
export function homeFor(role) {
  switch (role) {
    case RECRUITER:
      return '/jobs'
    case CANDIDATE:
      return '/openings'
    default:
      return '/login'
  }
}
