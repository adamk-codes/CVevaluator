import { Badge } from './ui'

/**
 * Authoring the requirements list.
 *
 * <h2>Ids are never sent</h2>
 *
 * Every row goes to the server with its id omitted, so JobRequirementsValidator
 * assigns R1..Rn by position. This is the one detail worth understanding here.
 * The validator treats ids as all-or-nothing - a list where some rows carry an
 * id and some do not is rejected outright - and an editor that preserved ids
 * for existing rows while leaving new ones blank would produce exactly that
 * mixed list the moment a requirement is added to a saved job.
 *
 * The cost is real and is the backend's stated design rather than an accident:
 * ids are positional, so deleting R2 renumbers everything below it. An
 * evaluation made against the old list still displays correctly because each
 * assessment snapshots its own requirementText and the evaluation records the
 * requirementsVersion it was made against - see RequirementAssessment.
 */

// Mirrors JobRequirementsValidator. Duplicated on purpose: these are here to
// disable a button before a pointless round trip, not to enforce anything. The
// server re-checks every one of them and its message is what gets displayed.
export const MAX_REQUIREMENTS = 12
export const MAX_MUST_HAVES = 5

export const emptyRequirement = () => ({ text: '', kind: 'MUST_HAVE' })

/** Strips blank rows and drops ids. What actually goes on the wire. */
export const toPayload = (requirements) =>
  requirements
    .filter((r) => r.text.trim())
    .map((r) => ({ text: r.text.trim(), kind: r.kind }))

/** The client-side reason this list cannot be saved, or null if it can. */
export function validationError(requirements) {
  const filled = toPayload(requirements)
  if (filled.length === 0) return 'Add at least one requirement.'
  if (filled.length > MAX_REQUIREMENTS) return `At most ${MAX_REQUIREMENTS} requirements.`

  const mustHaves = filled.filter((r) => r.kind === 'MUST_HAVE').length
  if (mustHaves === 0) return 'At least one requirement must be a must-have.'
  if (mustHaves > MAX_MUST_HAVES) return `At most ${MAX_MUST_HAVES} must-haves — if everything is required, nothing is.`
  return null
}

export default function RequirementsEditor({ requirements, onChange, disabled = false }) {
  const mustHaves = requirements.filter((r) => r.kind === 'MUST_HAVE' && r.text.trim()).length

  const update = (index, patch) =>
    onChange(requirements.map((r, i) => (i === index ? { ...r, ...patch } : r)))

  const add = () => onChange([...requirements, emptyRequirement()])

  const remove = (index) => onChange(requirements.filter((_, i) => i !== index))

  return (
    <div>
      <div className="row" style={{ marginBottom: 10 }}>
        <label style={{ marginBottom: 0 }}>Requirements</label>
        <div className="header-spacer" />
        <span className="hint" style={{ marginTop: 0 }}>
          {requirements.length}/{MAX_REQUIREMENTS} · {mustHaves}/{MAX_MUST_HAVES} must-have
        </span>
      </div>

      <div className="stack" style={{ gap: 10 }}>
        {requirements.map((requirement, index) => (
          <div key={index} className="row" style={{ gap: 8, alignItems: 'flex-start' }}>
            <span
              className="assessment-id"
              style={{ paddingTop: 11, width: 26, flex: 'none' }}
              title="Assigned by the server on save, by position"
            >
              R{index + 1}
            </span>

            <input
              value={requirement.text}
              onChange={(e) => update(index, { text: e.target.value })}
              placeholder="Atomic and checkable against a CV, e.g. 3+ years of Java"
              disabled={disabled}
              style={{ flex: 1 }}
            />

            <select
              value={requirement.kind}
              onChange={(e) => update(index, { kind: e.target.value })}
              disabled={disabled}
              style={{ width: 150, flex: 'none' }}
            >
              <option value="MUST_HAVE">Must have</option>
              <option value="NICE_TO_HAVE">Nice to have</option>
            </select>

            <button
              type="button"
              className="small"
              onClick={() => remove(index)}
              disabled={disabled || requirements.length === 1}
              title={requirements.length === 1 ? 'A job needs at least one requirement' : 'Remove'}
              style={{ flex: 'none' }}
            >
              ✕
            </button>
          </div>
        ))}
      </div>

      <div className="row" style={{ marginTop: 12 }}>
        <button
          type="button"
          className="small"
          onClick={add}
          disabled={disabled || requirements.length >= MAX_REQUIREMENTS}
        >
          + Add requirement
        </button>
        <span className="hint" style={{ marginTop: 0 }}>
          A candidate lacking a <Badge value="MUST_HAVE" className="badge-kind" /> is out regardless
          of other strengths.
        </span>
      </div>
    </div>
  )
}
