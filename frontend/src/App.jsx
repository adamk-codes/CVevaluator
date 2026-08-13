import { Link, Navigate, Route, Routes } from 'react-router-dom'
import AppHeader from './components/AppHeader'
import RequireAuth from './auth/RequireAuth'
import { useAuth } from './auth/AuthContext'
import { homeFor } from './auth/routes'
import { CANDIDATE, RECRUITER } from './auth/session'

import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'

import JobsPage from './pages/recruiter/JobsPage'
import NewJobPage from './pages/recruiter/NewJobPage'
import JobPage from './pages/recruiter/JobPage'
import EvaluationPage from './pages/recruiter/EvaluationPage'

import OpeningsPage from './pages/candidate/OpeningsPage'
import OpeningPage from './pages/candidate/OpeningPage'
import MyApplicationsPage from './pages/candidate/MyApplicationsPage'

/**
 * Two personas, two route groups, one guard each.
 *
 * <p>The split is by role rather than by feature flags on shared screens. A
 * recruiter and a candidate looking at "a job" want genuinely different pages —
 * one edits requirements and reads verdicts, the other reads a description and
 * uploads a CV — so they are different components at different URLs rather than
 * one component full of conditionals.
 *
 * <p>Evaluations are reachable only from the recruiter group. That is a product
 * decision, not an oversight; see MyApplicationsPage for why a candidate is not
 * shown the assessment of their own CV.
 */
export default function App() {
  return (
    <>
      <AppHeader />

      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />

        <Route element={<RequireAuth role={RECRUITER} />}>
          <Route path="/jobs" element={<JobsPage />} />
          <Route path="/jobs/new" element={<NewJobPage />} />
          <Route path="/jobs/:jobId" element={<JobPage />} />
          <Route path="/jobs/:jobId/applications/:applicationId" element={<EvaluationPage />} />
        </Route>

        <Route element={<RequireAuth role={CANDIDATE} />}>
          <Route path="/openings" element={<OpeningsPage />} />
          <Route path="/openings/:jobId" element={<OpeningPage />} />
          <Route path="/applications" element={<MyApplicationsPage />} />
        </Route>

        <Route path="/" element={<Home />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </>
  )
}

/** Root sends each persona to its own landing page, and everyone else to sign in. */
function Home() {
  const { isAuthenticated, role } = useAuth()
  return <Navigate to={isAuthenticated ? homeFor(role) : '/login'} replace />
}

function NotFound() {
  const { isAuthenticated, role } = useAuth()
  return (
    <main className="page">
      <div className="empty">
        <h3>Page not found</h3>
        <p>
          <Link to={isAuthenticated ? homeFor(role) : '/login'}>
            {isAuthenticated ? 'Back to your dashboard' : 'Sign in'}
          </Link>
        </p>
      </div>
    </main>
  )
}
