import { authHeaders, logout } from '../auth/session'

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
  // Whatever identifies the caller comes from the auth seam. This file does not
  // know that it is currently a bearer token, and did not know when it was an
  // X-User-Id header - which is why swapping one for the other did not touch it.
  // See src/auth/session.js.
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

/**
 * Every posting on the platform. The candidate's browse.
 *
 * <p>Not what a recruiter's dashboard should call — see {@link listMyJobs}.
 */
export const listJobs = () => request('/api/jobs')

/**
 * The signed-in recruiter's own postings.
 *
 * <p>The dashboard used {@link listJobs} and listed every posting on the
 * platform, including other recruiters'. Opening one of those rendered its
 * details and then failed on the applicant list, which is ownership-scoped —
 * "Job not found" under a job plainly on the screen.
 */
export const listMyJobs = () => request('/api/me/jobs')

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

export function submitCv(jobId, file) {
  const form = new FormData()
  form.append('file', file)
  return request(`/api/jobs/${jobId}/applications`, {
    method: 'POST',
    body: form,
    isMultipart: true,
  })
}

/* ------------------------------------------ a candidate's own submissions --- */

/**
 * Every CV the signed-in candidate has submitted, newest first.
 *
 * <p>Takes no id. The server reads the subject from the token, so there is no
 * parameter here that could name anyone else — see {@code MyApplicationsController}.
 *
 * <p>This replaced a localStorage list of ids that were re-fetched one by one,
 * which existed only because the endpoint did not. That approach was
 * per-browser: the same candidate on a second machine saw an empty history.
 * Anything still reading {@code cvevaluator.submissions.*} from storage is a
 * leftover — nothing writes it now.
 */
export const listMyApplications = () => request('/api/me/applications')

/* ------------------------------------------------------------ the CV file --- */

/**
 * Opens the stored CV in a new tab, or downloads it for non-PDF formats.
 *
 * <p>Fetched and turned into a blob rather than pointed at with an
 * {@code <a href>}. The endpoint needs an {@code Authorization} header and a
 * plain link cannot carry one — the browser would send an anonymous request and
 * get a 401. The alternative, a token in the query string, would put a
 * credential in history, logs and any Referer the page later sends.
 *
 * <p>The object URL is revoked on a timer rather than immediately: revoking it
 * in the same tick can beat the new tab's own load, and the tab then opens
 * blank. A minute is far longer than the handover needs and the blob is freed
 * either way when the page unloads.
 */
export async function openCvFile(jobId, applicationId) {
  const response = await fetch(`/api/jobs/${jobId}/applications/${applicationId}/file`, {
    headers: authHeaders(),
  })

  if (!response.ok) {
    const text = await response.text()
    const body = text ? JSON.parse(text) : null
    throw new ApiError(response.status, body?.message ?? 'Could not open the CV.')
  }

  const url = URL.createObjectURL(await response.blob())
  window.open(url, '_blank', 'noopener,noreferrer')
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
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
