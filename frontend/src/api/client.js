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
 * Auth is stubbed by design: the backend reads `X-User-Id` via
 * HeaderCurrentUserProvider and there is no login. The id lives in
 * localStorage so a page reload keeps it, and the header switcher writes it.
 *
 * data.sql seeds exactly one user - the recruiter, id 1 - so that is the
 * default. Sending an id with no matching row gets a 400 "Current user not
 * found", not a 500, which is why the user switcher is safe to expose.
 */
const USER_ID_KEY = 'cvevaluator.userId'

export function currentUserId() {
  return localStorage.getItem(USER_ID_KEY) ?? '1'
}

export function setCurrentUserId(id) {
  localStorage.setItem(USER_ID_KEY, String(id))
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
  if (response.status === 204) return null

  const text = await response.text()
  const body = text ? JSON.parse(text) : null

  if (!response.ok) {
    throw new ApiError(response.status, body?.message ?? response.statusText)
  }
  return body
}

function request(path, { method = 'GET', body, isMultipart = false } = {}) {
  const headers = { 'X-User-Id': currentUserId() }

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

export function submitCv(jobId, file) {
  const form = new FormData()
  form.append('file', file)
  return request(`/api/jobs/${jobId}/applications`, {
    method: 'POST',
    body: form,
    isMultipart: true,
  })
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
