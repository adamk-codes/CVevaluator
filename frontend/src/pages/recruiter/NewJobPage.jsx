import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useCreateJob } from '../../api/queries'
import RequirementsEditor, {
  emptyRequirement,
  toPayload,
  validationError,
} from '../../components/RequirementsEditor'
import { ErrorAlert } from '../../components/ui'

export default function NewJobPage() {
  const navigate = useNavigate()
  const createJob = useCreateJob()

  const [title, setTitle] = useState('')
  const [seniority, setSeniority] = useState('Mid-level')
  const [description, setDescription] = useState('')
  const [requirements, setRequirements] = useState([emptyRequirement()])

  const requirementsError = validationError(requirements)
  const canSubmit = title.trim() && seniority.trim() && !requirementsError && !createJob.isPending

  function submit(event) {
    event.preventDefault()
    if (!canSubmit) return

    createJob.mutate(
      {
        title: title.trim(),
        seniority: seniority.trim(),
        description: description.trim(),
        requirements: toPayload(requirements),
      },
      { onSuccess: (job) => navigate(`/jobs/${job.id}`) },
    )
  }

  return (
    <main className="page">
      <Link to="/jobs" className="back-link">
        ← Jobs
      </Link>

      <div className="page-head">
        <div className="page-head-text">
          <h1>New job</h1>
          <p className="subtitle">
            Requirements are authored here and never inferred. The model decides whether a CV
            satisfies them — not what they are.
          </p>
        </div>
      </div>

      <form onSubmit={submit} className="stack">
        <div className="card">
          <div className="field">
            <label htmlFor="title">Title</label>
            <input
              id="title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={200}
              placeholder="Backend Engineer"
              required
            />
          </div>

          <div className="field">
            <label htmlFor="seniority">Seniority</label>
            <input
              id="seniority"
              value={seniority}
              onChange={(e) => setSeniority(e.target.value)}
              maxLength={50}
              placeholder="Mid-level"
              required
            />
          </div>

          <div className="field" style={{ marginBottom: 0 }}>
            <label htmlFor="description">Description</label>
            <textarea
              id="description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              maxLength={5000}
              placeholder="What the role involves. Context for the reader — the requirements below are what a CV is graded against."
            />
            <p className="hint">{description.length}/5000</p>
          </div>
        </div>

        <div className="card">
          <RequirementsEditor
            requirements={requirements}
            onChange={setRequirements}
            disabled={createJob.isPending}
          />
        </div>

        {createJob.error && <ErrorAlert error={createJob.error} />}

        <div className="row">
          <button type="submit" className="primary" disabled={!canSubmit}>
            {createJob.isPending ? 'Creating…' : 'Create job'}
          </button>
          {requirementsError && <span className="hint" style={{ marginTop: 0 }}>{requirementsError}</span>}
        </div>
      </form>
    </main>
  )
}
