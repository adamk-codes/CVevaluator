/**
 * Small shared presentational pieces. No data fetching in here.
 */

/** Enum values arrive as NOT_A_FIT and have to read as "Not a fit". */
export const humanise = (value) =>
  !value ? '' : value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ')

export const formatDate = (iso) =>
  new Date(iso).toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })

export function formatBytes(bytes) {
  if (!bytes) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * One badge component for every enum in the system - RequirementStatus,
 * Verdict and ApplicationStatus. They never collide because no two of them
 * share a value, and the CSS keys off the raw enum name so adding a value
 * backend-side shows up here as an unstyled badge rather than a wrong-coloured
 * one.
 */
export const Badge = ({ value, className = '' }) => (
  <span className={`badge badge-${value} ${className}`}>{humanise(value)}</span>
)

export const Spinner = ({ label }) => (
  <span className="spinner-line">
    <span className="spinner" aria-hidden="true" />
    {label}
  </span>
)

export const ErrorAlert = ({ error }) => (
  <div className="alert alert-error" role="alert">
    {error?.message ?? 'Something went wrong.'}
  </div>
)

export const Empty = ({ title, children }) => (
  <div className="empty">
    <h3>{title}</h3>
    {children}
  </div>
)

/**
 * A 0-5 dimension score as five pips.
 *
 * Pips rather than a number alone because the scale is short and fixed - the
 * shape of "3 of 5" reads faster than the digit, and it makes the anchors
 * visible without a legend.
 */
export const ScoreMeter = ({ score, max = 5 }) => (
  <div className="meter" role="img" aria-label={`${score} out of ${max}`}>
    {Array.from({ length: max }, (_, i) => (
      <span key={i} className={`meter-pip ${i < score ? 'filled' : ''}`} />
    ))}
  </div>
)
