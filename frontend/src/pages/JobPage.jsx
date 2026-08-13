import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useApplications, useJob, useReplaceRequirements, isInFlight } from '../api/queries'
import RequirementsEditor, { toPayload, validationError } from '../components/RequirementsEditor'
import CvUpload from '../components/CvUpload'
import { Badge, Empty, ErrorAlert, Spinner, formatBytes, formatDate } from '../components/ui'

export default function JobPage() {
  const { jobId } = useParams()
  const { data: job, isPending, error } = useJob(jobId)

  if (isPending) {
    return (
      <main className="page">
        <Spinner label="Loading job…" />
      </main>
    )
  }
  if (error) {
    return (
      <main className="page">
        <Link to="/jobs" className="back-link">← Jobs</Link>
        <ErrorAlert error={error} />
      </main>
    )
  }

  return (
    <main className="page">
      <Link to="/jobs" className="back-link">← Jobs</Link>

      <div className="page-head">
        <div className="page-head-text">
          <h1>{job.title}</h1>
          <p className="subtitle">{job.description}</p>
        </div>
        <span className="badge badge-kind">{job.seniority}</span>
      </div>

      <div className="stack">
        <RequirementsCard job={job} />

        <div className="card">
          <div className="card-head">
            <h2>Submit a CV</h2>
          </div>
          <CvUpload jobId={jobId} />
        </div>

        <ApplicationsCard jobId={jobId} />
      </div>
    </main>
  )
}

/**
 * Requirements, read-only until "Edit" is pressed.
 *
 * Editing is behind a toggle rather than always-live because saving is not a
 * cheap write: PUT /requirements re-evaluates every CV already on the job. A
 * form that saved as you typed would fire a burst of model calls per keystroke.
 */
function RequirementsCard({ job }) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState([])
  const replace = useReplaceRequirements(job.id)

  const draftError = validationError(draft)

  function startEditing() {
    setDraft(job.requirements.map((r) => ({ text: r.text, kind: r.kind })))
    replace.reset()
    setEditing(true)
  }

  function save() {
    if (draftError) return
    replace.mutate(toPayload(draft), { onSuccess: () => setEditing(false) })
  }

  return (
    <div className="card">
      <div className="card-head">
        <h2>Requirements</h2>
        <span className="badge badge-kind">v{job.requirementsVersion}</span>
        {!editing && (
          <button className="small" onClick={startEditing}>
            Edit
          </button>
        )}
      </div>

      {!editing && (
        <div>
          {job.requirements.map((requirement) => (
            <div key={requirement.id} className="requirement-row">
              <span className="assessment-id" style={{ paddingTop: 2, width: 26, flex: 'none' }}>
                {requirement.id}
              </span>
              <span className="grow">{requirement.text}</span>
              <Badge value={requirement.kind} className="badge-kind" />
            </div>
          ))}
        </div>
      )}

      {editing && (
        <div className="stack">
          <div className="alert alert-info">
            Saving re-evaluates every CV already submitted to this job, in the background. Ids are
            reassigned by position, so an evaluation made against v{job.requirementsVersion} keeps
            showing the requirement text it was actually judged against.
          </div>

          <RequirementsEditor
            requirements={draft}
            onChange={setDraft}
            disabled={replace.isPending}
          />

          {replace.error && <ErrorAlert error={replace.error} />}

          <div className="row">
            <button className="primary" onClick={save} disabled={Boolean(draftError) || replace.isPending}>
              {replace.isPending ? 'Saving…' : 'Save requirements'}
            </button>
            <button onClick={() => setEditing(false)} disabled={replace.isPending}>
              Cancel
            </button>
            {draftError && <span className="hint" style={{ marginTop: 0 }}>{draftError}</span>}
          </div>
        </div>
      )}
    </div>
  )
}

function ApplicationsCard({ jobId }) {
  const { data: applications, isPending, error } = useApplications(jobId)
  const pendingCount = applications?.filter((app) => isInFlight(app.status)).length ?? 0

  return (
    <div className="card">
      <div className="card-head">
        <h2>Submitted CVs</h2>
        {pendingCount > 0 && <Spinner label={`${pendingCount} processing`} />}
      </div>

      {isPending && <Spinner label="Loading CVs…" />}
      {error && <ErrorAlert error={error} />}

      {applications?.length === 0 && (
        <Empty title="No CVs yet">
          <p>Submit one above and it will appear here as it is processed.</p>
        </Empty>
      )}

      {applications?.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>File</th>
              <th>Status</th>
              <th>Size</th>
              <th>Extracted</th>
              <th>Submitted</th>
            </tr>
          </thead>
          <tbody>
            {applications.map((app) => (
              <tr key={app.id}>
                <td>
                  <Link to={`/jobs/${jobId}/applications/${app.id}`} className="filename">
                    {app.originalFilename}
                  </Link>
                  {app.status === 'FAILED' && app.failureReason && (
                    <div className="hint" style={{ color: 'var(--not-met)' }}>
                      {app.failureReason}
                    </div>
                  )}
                </td>
                <td><Badge value={app.status} /></td>
                <td className="numeric">{formatBytes(app.sizeBytes)}</td>
                <td className="numeric">
                  {app.textLength > 0 ? `${app.textLength.toLocaleString()} chars` : '—'}
                  {app.extractionMethod && (
                    <span className="hint" style={{ marginTop: 0, display: 'block' }}>
                      {app.extractionMethod}
                    </span>
                  )}
                </td>
                <td className="numeric">{formatDate(app.submittedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
