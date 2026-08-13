import { Link, Navigate, Route, Routes } from 'react-router-dom'
import UserSwitcher from './components/UserSwitcher'
import JobsPage from './pages/JobsPage'
import NewJobPage from './pages/NewJobPage'
import JobPage from './pages/JobPage'
import EvaluationPage from './pages/EvaluationPage'

export default function App() {
  return (
    <>
      <header className="app-header">
        <div className="app-header-inner">
          <Link to="/jobs" className="brand">
            CV<span>Evaluator</span>
          </Link>
          <div className="header-spacer" />
          <UserSwitcher />
        </div>
      </header>

      <Routes>
        <Route path="/" element={<Navigate to="/jobs" replace />} />
        <Route path="/jobs" element={<JobsPage />} />
        <Route path="/jobs/new" element={<NewJobPage />} />
        <Route path="/jobs/:jobId" element={<JobPage />} />
        {/*
          The evaluation lives under the job even though
          GET /api/applications/{id}/evaluation is not nested that way. The URL
          describes where the user is, not which endpoint backs it: they got
          here from a job's CV list and the back link has to lead there.
        */}
        <Route path="/jobs/:jobId/applications/:applicationId" element={<EvaluationPage />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </>
  )
}

function NotFound() {
  return (
    <main className="page">
      <div className="empty">
        <h3>Page not found</h3>
        <p>
          <Link to="/jobs">Back to jobs</Link>
        </p>
      </div>
    </main>
  )
}
