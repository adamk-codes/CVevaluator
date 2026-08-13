import { Link, useParams } from 'react-router-dom'
import {
  isInFlight,
  useApplication,
  useEvaluation,
  useEvaluationHistory,
} from '../api/queries'
import {
  Badge,
  Empty,
  ErrorAlert,
  ScoreMeter,
  Spinner,
  formatDate,
  humanise,
} from '../components/ui'

const DIMENSION_LABELS = {
  IMPACT_AND_OWNERSHIP: 'Impact & ownership',
  COMMUNICATION_QUALITY: 'Communication quality',
}

export default function EvaluationPage() {
  const { jobId, applicationId } = useParams()

  const application = useApplication(jobId, applicationId)

  // Gated on extraction finishing. Polling the evaluation endpoint while the CV
  // is still PENDING is pure noise - it cannot exist yet by construction, since
  // the evaluation is fired from the event that records the extracted text.
  const extractionDone = application.data?.status === 'COMPLETED'
  const evaluation = useEvaluation(applicationId, { enabled: extractionDone })

  if (application.isPending) {
    return (
      <Shell jobId={jobId}>
        <Spinner label="Loading…" />
      </Shell>
    )
  }
  if (application.error) {
    return (
      <Shell jobId={jobId}>
        <ErrorAlert error={application.error} />
      </Shell>
    )
  }

  const app = application.data

  return (
    <Shell jobId={jobId}>
      <div className="page-head">
        <div className="page-head-text">
          <h1>{app.originalFilename}</h1>
          <p className="subtitle">
            Submitted {formatDate(app.submittedAt)}
            {app.extractionMethod && ` · extracted with ${app.extractionMethod}`}
            {app.textLength > 0 && ` · ${app.textLength.toLocaleString()} characters`}
          </p>
        </div>
        <Badge value={app.status} />
      </div>

      <div className="stack">
        <PipelineState app={app} evaluation={evaluation} />

        {evaluation.data && (
          <>
            <VerdictBanner evaluation={evaluation.data} />
            <Assessments assessments={evaluation.data.assessments} />
            <Dimensions scores={evaluation.data.dimensionScores} />
            <Provenance evaluation={evaluation.data} />
            <History applicationId={applicationId} currentId={evaluation.data.id} />
          </>
        )}
      </div>
    </Shell>
  )
}

const Shell = ({ jobId, children }) => (
  <main className="page">
    <Link to={`/jobs/${jobId}`} className="back-link">
      ← Job
    </Link>
    {children}
  </main>
)

/**
 * What the pipeline is doing, when there is not yet a verdict to show.
 *
 * The two stages are reported separately on purpose. "Extracting" and
 * "evaluating" fail for completely different reasons - a scanned PDF versus a
 * model that returned an ungrounded quote - and a single "processing" spinner
 * covering both would make the difference invisible at exactly the moment
 * somebody asks about it.
 */
function PipelineState({ app, evaluation }) {
  if (isInFlight(app.status)) {
    return (
      <div className="card">
        <Spinner label={
          app.status === 'PENDING'
            ? 'Queued for text extraction…'
            : 'Extracting text from the CV…'
        } />
      </div>
    )
  }

  if (app.status === 'FAILED') {
    return (
      <div className="card">
        <div className="alert alert-error">
          <strong>Extraction failed.</strong> {app.failureReason}
        </div>
        <p className="hint">
          There is no OCR in this system by design — a PDF with no text layer fails here rather
          than being guessed at.
        </p>
      </div>
    )
  }

  if (evaluation.error) {
    return (
      <div className="card">
        <ErrorAlert error={evaluation.error} />
      </div>
    )
  }

  if (!evaluation.data) {
    // isFetching distinguishes "still polling" from "gave up". useEvaluation
    // stops after a bounded number of attempts because an evaluation can
    // legitimately never arrive - an unconfigured API key, or a model response
    // the grounding checker rejected, both leave the CV COMPLETED with no
    // evaluation row and nothing further scheduled.
    const stillWaiting = evaluation.isFetching || evaluation.isPending

    return (
      <div className="card">
        {stillWaiting ? (
          <Spinner label="Text extracted. Waiting for the evaluation…" />
        ) : (
          <Empty title="No evaluation yet">
            <p>
              The CV extracted cleanly but no evaluation has been recorded. That happens when the
              model call is not configured, or when its response was rejected — an assessment
              quoting text that is not in the CV is discarded rather than shown.
            </p>
            <p className="hint">
              Editing this job&apos;s requirements re-runs the evaluation. The server log has the
              reason.
            </p>
          </Empty>
        )}
      </div>
    )
  }

  return null
}

function VerdictBanner({ evaluation }) {
  return (
    <div className="verdict-banner">
      <div>
        <div className="verdict-label">Verdict</div>
        <div className={`verdict-value verdict-${evaluation.verdict}`}>
          {humanise(evaluation.verdict)}
        </div>
      </div>
      <div className="divider" style={{ width: 1, height: 42, margin: 0 }} />
      <p className="summary">{evaluation.summary}</p>
    </div>
  )
}

/**
 * The per-requirement breakdown.
 *
 * Reasoning is rendered above the status, matching the order the model wrote
 * them in - RequirementAssessment fixes that field order so the argument is
 * committed to before the verdict is. Presenting the status first here would
 * undo the point of it on screen even though the data underneath is unchanged.
 */
function Assessments({ assessments }) {
  return (
    <div className="card">
      <div className="card-head">
        <h2>Requirements</h2>
        <span className="hint" style={{ marginTop: 0 }}>
          {assessments.filter((a) => a.status === 'MET').length} of {assessments.length} met
        </span>
      </div>

      <div className="stack">
        {assessments.map((assessment) => (
          <div key={assessment.requirementId} className="assessment">
            <div className="assessment-head">
              <span className="assessment-id">{assessment.requirementId}</span>
              <Badge value={assessment.requirementKind} className="badge-kind" />
              <div className="header-spacer" />
              <Badge value={assessment.status} />
            </div>

            <p className="requirement-text">{assessment.requirementText}</p>
            <p className="reasoning">{assessment.reasoning}</p>

            {assessment.evidenceQuote ? (
              <div className="evidence">
                <span className="evidence-label">Verbatim from CV</span>
                <blockquote>“{assessment.evidenceQuote}”</blockquote>
              </div>
            ) : (
              <p className="no-evidence">
                No quote — {assessment.status === 'UNCLEAR'
                  ? 'the CV is silent on this requirement.'
                  : 'an absence cannot be quoted.'}
              </p>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

function Dimensions({ scores }) {
  return (
    <div className="card">
      <div className="card-head">
        <h2>Rubric scores</h2>
        <span className="hint" style={{ marginTop: 0 }}>
          Not part of the verdict — a rubric score never rescues a failed must-have
        </span>
      </div>

      <div className="stack">
        {scores.map((score) => (
          <div key={score.dimension} className="dimension">
            <div className="dimension-head">
              <span className="dimension-name">
                {DIMENSION_LABELS[score.dimension] ?? humanise(score.dimension)}
              </span>
              <span className="dimension-score">{score.score}/5</span>
            </div>
            <ScoreMeter score={score.score} />
            <p className="reasoning" style={{ fontSize: '0.92rem' }}>{score.reasoning}</p>
            {score.evidenceQuote && (
              <div className="evidence">
                <span className="evidence-label">Verbatim from CV</span>
                <blockquote>“{score.evidenceQuote}”</blockquote>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

/** What produced this answer. Dull, and the first thing asked about when a verdict is disputed. */
function Provenance({ evaluation }) {
  return (
    <div className="card">
      <div className="card-head">
        <h2>Provenance</h2>
      </div>
      <div className="meta">
        <span>Prompt {evaluation.promptVersion}</span>
        <span>Requirements v{evaluation.evaluatedAgainstRequirementsVersion}</span>
        <span>{evaluation.tokensIn.toLocaleString()} tokens in</span>
        <span>{evaluation.tokensOut.toLocaleString()} tokens out</span>
        <span>{(evaluation.latencyMs / 1000).toFixed(1)}s</span>
        <span>Evaluated {formatDate(evaluation.createdAt)}</span>
      </div>
    </div>
  )
}

/**
 * Earlier evaluations of the same CV, newest first.
 *
 * Only rendered when there is more than one, since a history of a single entry
 * that is already displayed above adds nothing. The backend retains at most
 * cvevaluator.evaluation.max-per-application of these.
 */
function History({ applicationId, currentId }) {
  const { data: history } = useEvaluationHistory(applicationId)
  const earlier = history?.filter((entry) => entry.id !== currentId) ?? []

  if (earlier.length === 0) return null

  return (
    <div className="card">
      <div className="card-head">
        <h2>Earlier evaluations</h2>
      </div>
      <table>
        <thead>
          <tr>
            <th>Verdict</th>
            <th>Requirements</th>
            <th>Prompt</th>
            <th>Evaluated</th>
          </tr>
        </thead>
        <tbody>
          {earlier.map((entry) => (
            <tr key={entry.id}>
              <td><Badge value={entry.verdict} /></td>
              <td className="numeric">v{entry.evaluatedAgainstRequirementsVersion}</td>
              <td className="numeric">{entry.promptVersion}</td>
              <td className="numeric">{formatDate(entry.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
