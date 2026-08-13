import { Link } from 'react-router-dom'
import { useJobs } from '../api/queries'
import { Empty, ErrorAlert, Spinner, formatDate } from '../components/ui'

export default function JobsPage() {
  const { data: jobs, isPending, error } = useJobs()

  return (
    <main className="page">
      <div className="page-head">
        <div className="page-head-text">
          <h1>Jobs</h1>
          <p className="subtitle">Post a role, then evaluate the CVs that come in against it.</p>
        </div>
        <Link to="/jobs/new">
          <button className="primary">New job</button>
        </Link>
      </div>

      {isPending && <Spinner label="Loading jobs…" />}
      {error && <ErrorAlert error={error} />}

      {jobs?.length === 0 && (
        <Empty title="No jobs yet">
          <p>Create a job and author its requirements to get started.</p>
        </Empty>
      )}

      <div className="stack">
        {jobs?.map((job) => {
          const mustHaves = job.requirements.filter((r) => r.kind === 'MUST_HAVE').length
          return (
            <Link key={job.id} to={`/jobs/${job.id}`} className="card job-card">
              <div className="card-head" style={{ marginBottom: 8 }}>
                <h2>{job.title}</h2>
                <span className="badge badge-kind">{job.seniority}</span>
              </div>
              {job.description && (
                <p className="subtitle" style={{ margin: '0 0 12px' }}>
                  {job.description.length > 180
                    ? `${job.description.slice(0, 180)}…`
                    : job.description}
                </p>
              )}
              <div className="meta">
                <span>
                  {job.requirements.length} requirement{job.requirements.length === 1 ? '' : 's'}
                  {' · '}
                  {mustHaves} must-have{mustHaves === 1 ? '' : 's'}
                </span>
                <span>Requirements v{job.requirementsVersion}</span>
                <span>Posted {formatDate(job.createdAt)}</span>
              </div>
            </Link>
          )
        })}
      </div>
    </main>
  )
}
