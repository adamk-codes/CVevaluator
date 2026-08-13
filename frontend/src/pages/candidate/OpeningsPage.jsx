import { Link } from 'react-router-dom'
import { useJobs } from '../../api/queries'
import { Empty, ErrorAlert, Spinner, formatDate } from '../../components/ui'

/**
 * The candidate's view of the same jobs the recruiter posted.
 *
 * <p>Deliberately does not show requirement text. A candidate who can read the
 * exact checklist their CV will be graded against is being invited to write the
 * CV backwards from it, and every assessment after that is grading the
 * candidate's reading of the requirements rather than their experience. The
 * count is shown because "eleven requirements, five of them essential" is
 * useful for deciding whether to apply; the list is not.
 */
export default function OpeningsPage() {
  const { data: jobs, isPending, error } = useJobs()

  const open = jobs?.filter((job) => job.active)

  return (
    <main className="page">
      <div className="page-head">
        <div className="page-head-text">
          <h1>Open roles</h1>
          <p className="subtitle">Pick a role and submit your CV.</p>
        </div>
      </div>

      {isPending && <Spinner label="Loading roles…" />}
      {error && <ErrorAlert error={error} />}

      {open?.length === 0 && (
        <Empty title="No open roles right now">
          <p>Check back later.</p>
        </Empty>
      )}

      <div className="stack">
        {open?.map((job) => {
          const essential = job.requirements.filter((r) => r.kind === 'MUST_HAVE').length
          return (
            <Link key={job.id} to={`/openings/${job.id}`} className="card job-card">
              <div className="card-head" style={{ marginBottom: 8 }}>
                <h2>{job.title}</h2>
                <span className="badge badge-kind">{job.seniority}</span>
              </div>

              {job.description && (
                <p className="subtitle" style={{ margin: '0 0 12px' }}>
                  {job.description.length > 200
                    ? `${job.description.slice(0, 200)}…`
                    : job.description}
                </p>
              )}

              <div className="meta">
                <span>
                  {job.requirements.length} requirement{job.requirements.length === 1 ? '' : 's'}
                  {essential > 0 && `, ${essential} essential`}
                </span>
                <span>Posted {formatDate(job.createdAt)}</span>
              </div>
            </Link>
          )
        })}
      </div>
    </main>
  )
}
