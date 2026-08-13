import { authHeaders, loadSession, logout } from '../auth/session'

/**
 * The one place that talks to the backend.
 *
 * Every response the API can produce is either a DTO or an ErrorResponse
 * record `{status, message, timestamp}` from GlobalExceptionHandler - there is
 * no Spring default error page and no stack trace, so `message` is always safe
 * to render. That is what makes `ApiError.message` below usable directly in the
 * UI instead of a generic "something went wrong".
 */

/** Thrown for any non-2xx. Carries the server's authored message. */
export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/**
 * Reads the body once and turns a failure into an ApiError.
 *
 * A 204 and a 404 both have no JSON body worth parsing, so the body is only
 * read when there is one. Calling `.json()` on an empty 204 throws a
 * SyntaxError that would surface as a confusing "Unexpected end of JSON input"
 * rather than the actual outcome.
 */
async function handle(response) {
  // A token that expired or was revoked mid-session. Every subsequent request
  // would 401 too, so the session is dropped here rather than letting each
  // screen render its own "Authentication required" against a header still
  // showing the user's name.
  //
  // A full location change rather than a router navigation: this module is not
  // inside React and has no router to call, and starting from a clean load is
  // the right outcome anyway - it clears every cache holding data fetched under
  // the dead token. Guarded so a 401 from the sign-in screen itself, which is
  // just wrong credentials, does not reload the page out from under the form.
  if (response.status === 401 && !window.location.pathname.startsWith('/login')) {
    logout()
    window.location.assign('/login')
    throw new ApiError(401, 'Your session has expired. Please sign in again.')
  }

  if (response.status === 204) return null

  const text = await response.text()
  const body = text ? JSON.parse(text) : null

  if (!response.ok) {
    throw new ApiError(response.status, body?.message ?? response.statusText)
  }
  return body
}

function request(path, { method = 'GET', body, isMultipart = false } = {}) {
  // Whatever identifies the caller comes from the auth seam - today an
  // X-User-Id header, later a bearer token. This file deliberately does not
  // know which. See src/auth/session.js.
  const headers = { ...authHeaders() }

  // fetch sets multipart/form-data itself, including the boundary. Setting
  // Content-Type by hand here omits the boundary and Spring rejects the whole
  // request as a malformed multipart before the controller is reached.
  if (!isMultipart && body !== undefined) headers['Content-Type'] = 'application/json'

  return fetch(path, {
    method,
    headers,
    body: isMultipart ? body : body !== undefined ? JSON.stringify(body) : undefined,
  }).then(handle)
}

/* ---------------------------------------------------------------- jobs --- */

export const listJobs = () => request('/api/jobs')

export const getJob = (jobId) => request(`/api/jobs/${jobId}`)

export const createJob = (job) => request('/api/jobs', { method: 'POST', body: job })

/**
 * Replaces the requirements list entire - PUT, not PATCH, because the backend
 * treats the body as the new list and a job sent three requirements is left
 * with three. Triggers re-evaluation of every already-submitted CV on the job.
 */
export const replaceRequirements = (jobId, requirements) =>
  request(`/api/jobs/${jobId}/requirements`, { method: 'PUT', body: { requirements } })

/* -------------------------------------------------------- applications --- */

export const listApplications = (jobId) => request(`/api/jobs/${jobId}/applications`)

export const getApplication = (jobId, applicationId) =>
  request(`/api/jobs/${jobId}/applications/${applicationId}`)

export async function submitCv(jobId, file) {
  const form = new FormData()
  form.append('file', file)
  const created = await request(`/api/jobs/${jobId}/applications`, {
    method: 'POST',
    body: form,
    isMultipart: true,
  })
  remember(jobId, created.id)
  return created
}

/* ------------------------------------------ a candidate's own submissions --- */

/**
 * MISSING ENDPOINT — the backend has no "my applications" resource.
 *
 * <p>{@code GET /api/jobs/{jobId}/applications} is the recruiter's list and
 * returns <em>every</em> candidate's submission on that job. Calling it from a
 * candidate screen and filtering in the browser would put other people's
 * submissions in this user's memory and network log, which is a data leak
 * whether or not the UI draws them. So it is not used here.
 *
 * <p>Instead each submission this browser makes is remembered locally, and the
 * list is rebuilt by asking for those ids individually through the
 * already-scoped per-application endpoint. Nothing about anyone else is ever
 * requested.
 *
 * <p><strong>The limitation, stated plainly:</strong> this is per-browser. Sign
 * in from a different machine, or clear site data, and the candidate's history
 * looks empty even though the rows exist. That is why the screen says so rather
 * than presenting the list as authoritative.
 *
 * <p>SWAP — when {@code GET /api/me/applications} (or equivalent) exists, the
 * body of this function becomes a single {@code request(...)} call and
 * {@link remember} can be deleted along with its two call sites.
 */
export async function listMyApplications() {
  const remembered = readRemembered()

  const settled = await Promise.allSettled(
    remembered.map(({ jobId, applicationId }) => getApplication(jobId, applicationId)),
  )

  // Rejections are dropped rather than surfaced: a remembered id can legitimately
  // be gone (deleted job, wiped dev database), and one stale entry must not blank
  // the whole page.
  return settled
    .filter((outcome) => outcome.status === 'fulfilled')
    .map((outcome) => outcome.value)
    .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt))
}

/** Scoped per user id, so two accounts on one browser do not see each other's list. */
const rememberKey = () => `cvevaluator.submissions.${loadSession()?.user?.id ?? 'anonymous'}`

function readRemembered() {
  try {
    const parsed = JSON.parse(localStorage.getItem(rememberKey()) ?? '[]')
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function remember(jobId, applicationId) {
  const existing = readRemembered()
  if (existing.some((entry) => entry.applicationId === applicationId)) return
  localStorage.setItem(
    rememberKey(),
    JSON.stringify([...existing, { jobId: Number(jobId), applicationId }]),
  )
}

/* --------------------------------------------------------- evaluations --- */

/**
 * The latest evaluation, or null when there is not one yet.
 *
 * The 404-to-null translation is the important part and it is deliberately
 * *not* generic. Evaluation is a second async stage that runs after extraction
 * completes, so between a CV reaching COMPLETED and its evaluation landing this
 * endpoint genuinely 404s - that is "not yet", not an error, and the polling in
 * useEvaluation depends on being able to tell the difference. Every other 404
 * in this client stays an ApiError.
 */
export function getEvaluation(applicationId) {
  return request(`/api/applications/${applicationId}/evaluation`).catch((error) => {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  })
}

export const getEvaluationHistory = (applicationId) =>
  request(`/api/applications/${applicationId}/evaluations`)

export const deleteEvaluation = (applicationId, evaluationId) =>
  request(`/api/applications/${applicationId}/evaluations/${evaluationId}`, { method: 'DELETE' })
