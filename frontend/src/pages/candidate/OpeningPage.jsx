import { Link, useParams } from 'react-router-dom'
import { useJob } from '../../api/queries'
import CvUpload from '../../components/CvUpload'
import { ErrorAlert, Spinner, formatDate } from '../../components/ui'

/**
 * One opening, with the apply form.
 *
 * <p>This is where CV upload lives. It used to sit on the recruiter's job page,
 * which had the recruiter submitting CVs on a candidate's behalf — convenient
 * for a demo and wrong as a model of who does what.
 */
export default function OpeningPage() {
  const { jobId } = useParams()
  const { data: job, isPending, error } = useJob(jobId)

  if (isPending) {
    return (
      <main className="page">
        <Spinner label="Loading role…" />
      </main>
    )
  }

  if (error) {
    return (
      <main className="page">
        <Link to="/openings" className="back-link">← Open roles</Link>
        <ErrorAlert error={error} />
      </main>
    )
  }

  return (
    <main className="page">
      <Link to="/openings" className="back-link">← Open roles</Link>

      <div className="page-head">
        <div className="page-head-text">
          <h1>{job.title}</h1>
          <p className="subtitle">Posted {formatDate(job.createdAt)}</p>
        </div>
        <span className="badge badge-kind">{job.seniority}</span>
      </div>

      <div className="stack">
        {job.description && (
          <div className="card">
            <div className="card-head">
              <h2>About the role</h2>
            </div>
            <p className="reasoning" style={{ whiteSpace: 'pre-wrap' }}>{job.description}</p>
          </div>
        )}

        <div className="card">
          <div className="card-head">
            <h2>Apply</h2>
          </div>

          <p className="hint" style={{ marginTop: 0, marginBottom: 14 }}>
            Your CV is assessed against {job.requirements.length} requirement
            {job.requirements.length === 1 ? '' : 's'} written by the recruiter. Every conclusion
            drawn about you has to be backed by a quote taken directly from your CV.
          </p>

          <CvUpload jobId={jobId} />
        </div>
      </div>
    </main>
  )
}
