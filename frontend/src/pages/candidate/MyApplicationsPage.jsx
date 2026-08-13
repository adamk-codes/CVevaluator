import { Link } from 'react-router-dom'
import { useMyApplications } from '../../api/queries'
import ViewCvButton from '../../components/ViewCvButton'
import { Badge, Empty, ErrorAlert, Spinner, formatBytes, formatDate } from '../../components/ui'

/**
 * What the candidate submitted and where each one has got to.
 *
 * <h2>No verdicts here, on purpose</h2>
 *
 * A candidate sees that their CV was received and read; they do not see the
 * assessment, the per-requirement statuses or the score. That is a product
 * decision worth stating rather than leaving implicit: the evaluation is a
 * recruiter's decision aid, it is explicitly not calibrated against human
 * reviewers, and showing a candidate a machine-generated "Not a fit" with
 * quotes from their own CV is a different and much heavier product than this
 * one.
 *
 * <p>The one thing they do see is a failure to read the file, because that is
 * actionable and it is theirs to fix — the backend already treats the
 * extraction failure reason as candidate-facing text and authors it
 * accordingly.
 *
 * <p>The list comes from {@code GET /api/me/applications}, scoped to the token
 * subject server-side, so it is the same on every device this candidate signs
 * in from.
 */
export default function MyApplicationsPage() {
  const { data: applications, isPending, error } = useMyApplications()

  return (
    <main className="page">
      <div className="page-head">
        <div className="page-head-text">
          <h1>My applications</h1>
          <p className="subtitle">Every CV you have submitted, newest first.</p>
        </div>
        <Link to="/openings">
          <button className="primary">Browse roles</button>
        </Link>
      </div>

      {isPending && <Spinner label="Loading your applications…" />}
      {error && <ErrorAlert error={error} />}

      {applications?.length === 0 && (
        <Empty title="Nothing submitted yet">
          <p>
            <Link to="/openings">Browse open roles</Link> and submit your CV.
          </p>
        </Empty>
      )}

      {applications?.length > 0 && (
        <div className="stack">
          {applications.map((app) => (
            <div key={app.id} className="card">
              <div className="card-head" style={{ marginBottom: 10 }}>
                <h2 style={{ fontSize: '1rem' }}>{app.originalFilename}</h2>
                {/* Same endpoint as the recruiter's, allowed by the same
                    ownership rule: a candidate may read back the document they
                    uploaded. Useful precisely when it FAILED and they need to
                    see which file they actually sent. */}
                <ViewCvButton jobId={app.jobId} applicationId={app.id} className="small" />
                <Badge value={app.status} />
              </div>

              <div className="meta">
                <span>Submitted {formatDate(app.submittedAt)}</span>
                <span>{formatBytes(app.sizeBytes)}</span>
                {app.textLength > 0 && <span>{app.textLength.toLocaleString()} characters read</span>}
              </div>

              {app.status === 'FAILED' && app.failureReason && (
                <div className="alert alert-error" style={{ marginTop: 12 }}>
                  <strong>We could not read this file.</strong> {app.failureReason}
                </div>
              )}

              {(app.status === 'PENDING' || app.status === 'PROCESSING') && (
                <p className="hint" style={{ marginTop: 10 }}>
                  Your CV is being read. This usually takes a few seconds.
                </p>
              )}

              {app.status === 'COMPLETED' && (
                <p className="hint" style={{ marginTop: 10 }}>
                  Received and read in full. The recruiter reviews it from here.
                </p>
              )}
            </div>
          ))}
        </div>
      )}
    </main>
  )
}
