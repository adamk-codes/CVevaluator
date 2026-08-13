import { useState } from 'react'
import { openCvFile } from '../api/client'

/**
 * Opens the stored CV.
 *
 * <p>A button rather than a link, because the request has to carry an
 * Authorization header — see {@code openCvFile}. It is styled as a link where
 * it sits in a table so it still reads as "open this thing", but it cannot be
 * an anchor without either dropping the credential or putting it in the URL.
 *
 * <p>A FAILED application still has a file: extraction failing means the text
 * could not be read, not that nothing was uploaded. That is exactly when a
 * recruiter most wants to look at the document, so this stays enabled.
 */
export default function ViewCvButton({ jobId, applicationId, className = '', children }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function open() {
    setBusy(true)
    setError(null)
    try {
      await openCvFile(jobId, applicationId)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <button type="button" className={className} onClick={open} disabled={busy}>
        {busy ? 'Opening…' : (children ?? 'View CV')}
      </button>
      {error && (
        <div className="hint" style={{ color: 'var(--not-met)', marginTop: 4 }}>
          {error}
        </div>
      )}
    </>
  )
}
