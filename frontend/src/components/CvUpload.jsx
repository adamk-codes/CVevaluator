import { useRef, useState } from 'react'
import { useSubmitCv } from '../api/queries'
import { ErrorAlert } from './ui'

// Mirrors cvevaluator.storage.allowed-extensions. The `accept` attribute is a
// file-picker filter and nothing more - a user can always pick "all files", and
// the real gate is FileSignatureValidator checking magic bytes server-side.
// Worth remembering that DOCX, XLSX and ZIP share the signature 50 4B 03 04, so
// the extension and the content check are not interchangeable.
const ACCEPT = '.pdf,.docx,.txt'

const MAX_BYTES = 10 * 1024 * 1024

export default function CvUpload({ jobId }) {
  const inputRef = useRef(null)
  const [file, setFile] = useState(null)
  const [localError, setLocalError] = useState(null)
  const submitCv = useSubmitCv(jobId)

  function choose(event) {
    const picked = event.target.files?.[0] ?? null
    setLocalError(
      picked && picked.size > MAX_BYTES ? 'File is too large. Maximum upload size is 10MB.' : null,
    )
    setFile(picked)
    submitCv.reset()
  }

  function upload(event) {
    event.preventDefault()
    if (!file || localError) return

    submitCv.mutate(file, {
      onSuccess: () => {
        setFile(null)
        // The input is uncontrolled, so its value has to be cleared by hand or
        // picking the same file again fires no change event and looks broken.
        if (inputRef.current) inputRef.current.value = ''
      },
    })
  }

  return (
    <form onSubmit={upload} className="stack" style={{ gap: 11 }}>
      <div className="row">
        <input
          ref={inputRef}
          type="file"
          accept={ACCEPT}
          onChange={choose}
          disabled={submitCv.isPending}
          style={{ flex: 1, minWidth: 240 }}
        />
        <button type="submit" className="primary" disabled={!file || Boolean(localError) || submitCv.isPending}>
          {submitCv.isPending ? 'Uploading…' : 'Submit CV'}
        </button>
      </div>

      <p className="hint" style={{ marginTop: 0 }}>
        PDF, DOCX or TXT, up to 10MB. A scanned PDF with no text layer is rejected with a readable
        reason — there is no OCR, by design.
      </p>

      {localError && <div className="alert alert-error">{localError}</div>}
      {submitCv.error && <ErrorAlert error={submitCv.error} />}
      {submitCv.isSuccess && (
        <div className="alert alert-info">
          Accepted. Extraction and evaluation run in the background — the row below updates itself.
        </div>
      )}
    </form>
  )
}
