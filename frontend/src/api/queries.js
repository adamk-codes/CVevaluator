import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as api from './client'

/**
 * Query keys, centralised so an invalidation cannot miss a cache entry by
 * spelling its key differently at the call site.
 */
export const keys = {
  jobs: ['jobs'],
  job: (jobId) => ['jobs', jobId],
  applications: (jobId) => ['jobs', jobId, 'applications'],
  myApplications: ['me', 'applications'],
  application: (jobId, applicationId) => ['jobs', jobId, 'applications', applicationId],
  evaluation: (applicationId) => ['applications', applicationId, 'evaluation'],
  evaluationHistory: (applicationId) => ['applications', applicationId, 'evaluations'],
}

/** A CV is still moving through the pipeline while it is in one of these. */
const IN_FLIGHT = ['PENDING', 'PROCESSING']

export const isInFlight = (status) => IN_FLIGHT.includes(status)

/* ---------------------------------------------------------------- jobs --- */

export function useJobs() {
  return useQuery({ queryKey: keys.jobs, queryFn: api.listJobs })
}

export function useJob(jobId) {
  return useQuery({
    queryKey: keys.job(jobId),
    queryFn: () => api.getJob(jobId),
    enabled: Boolean(jobId),
  })
}

export function useCreateJob() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: api.createJob,
    onSuccess: () => client.invalidateQueries({ queryKey: keys.jobs }),
  })
}

/**
 * Editing requirements re-evaluates every CV already on the job, so this
 * invalidates far more than the job itself.
 *
 * The evaluation caches are dropped wholesale rather than surgically: the PUT
 * returns before the re-evaluations finish (they run on a background pool), so
 * there is no moment at which this callback could know which applications now
 * have a new verdict. Dropping them all means each screen refetches when next
 * viewed and shows whatever the current answer is.
 */
export function useReplaceRequirements(jobId) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (requirements) => api.replaceRequirements(jobId, requirements),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.job(jobId) })
      client.invalidateQueries({ queryKey: keys.jobs })
      client.invalidateQueries({ queryKey: ['applications'] })
    },
  })
}

/* -------------------------------------------------------- applications --- */

/**
 * The CV list for one job, polled while anything on it is still extracting.
 *
 * The interval is a function rather than a number so polling stops on its own:
 * once every row is COMPLETED or FAILED there is nothing left to wait for, and
 * a fixed interval would keep hitting the API forever on an idle screen.
 */
export function useApplications(jobId) {
  return useQuery({
    queryKey: keys.applications(jobId),
    queryFn: () => api.listApplications(jobId),
    enabled: Boolean(jobId),
    refetchInterval: (query) =>
      query.state.data?.some((app) => isInFlight(app.status)) ? 2000 : false,
  })
}

export function useApplication(jobId, applicationId) {
  return useQuery({
    queryKey: keys.application(jobId, applicationId),
    queryFn: () => api.getApplication(jobId, applicationId),
    enabled: Boolean(jobId && applicationId),
    refetchInterval: (query) => (isInFlight(query.state.data?.status) ? 2000 : false),
  })
}

export function useSubmitCv(jobId) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (file) => api.submitCv(jobId, file),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.applications(jobId) })
      client.invalidateQueries({ queryKey: keys.myApplications })
    },
  })
}

/**
 * The signed-in candidate's own submissions, polled while any is still moving.
 *
 * Same self-terminating interval as {@link useApplications}: a screen where
 * everything has reached a terminal state stops asking.
 */
export function useMyApplications() {
  return useQuery({
    queryKey: keys.myApplications,
    queryFn: api.listMyApplications,
    refetchInterval: (query) =>
      query.state.data?.some((app) => isInFlight(app.status)) ? 2000 : false,
  })
}

/* --------------------------------------------------------- evaluations --- */

/**
 * The verdict for one CV, polled until it appears.
 *
 * Two things here are worth knowing, because both are places this would
 * silently misbehave if changed:
 *
 * 1. A CV reaching COMPLETED does not mean an evaluation exists. Extraction and
 *    evaluation are separate async stages, so this endpoint 404s for a while
 *    after the status goes terminal. `getEvaluation` maps that 404 to null and
 *    this keeps polling on null.
 *
 * 2. The polling has to give up. An evaluation can legitimately never arrive -
 *    LlmEvaluator refuses the call when no API key is configured, and
 *    SubmittedCvEvaluationListener swallows a rejected model response after
 *    logging it. Neither writes a row and neither changes the application
 *    status, so a client that polls on null forever would spin for the rest of
 *    the session against a CV that is never going to have a verdict. After
 *    `MAX_POLLS` this stops and the UI says so rather than showing a spinner
 *    that means nothing.
 */
const MAX_POLLS = 45

export function useEvaluation(applicationId, { enabled = true } = {}) {
  return useQuery({
    queryKey: keys.evaluation(applicationId),
    queryFn: () => api.getEvaluation(applicationId),
    enabled: Boolean(applicationId) && enabled,
    refetchInterval: (query) => {
      if (query.state.data) return false
      return query.state.dataUpdateCount < MAX_POLLS ? 2000 : false
    },
  })
}

export function useEvaluationHistory(applicationId) {
  return useQuery({
    queryKey: keys.evaluationHistory(applicationId),
    queryFn: () => api.getEvaluationHistory(applicationId),
    enabled: Boolean(applicationId),
  })
}

export function useDeleteEvaluation(applicationId) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (evaluationId) => api.deleteEvaluation(applicationId, evaluationId),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.evaluation(applicationId) })
      client.invalidateQueries({ queryKey: keys.evaluationHistory(applicationId) })
    },
  })
}
