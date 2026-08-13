import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { currentUserId, setCurrentUserId } from '../api/client'

/**
 * Sets the `X-User-Id` header every request carries.
 *
 * This exists because auth is stubbed by design - HeaderCurrentUserProvider
 * reads the header and there is no login to replace it with. It is a visible
 * control rather than a hardcoded constant so the seam is obvious at a demo:
 * this is the one thing a real JwtCurrentUserProvider would take over, and
 * nothing else in the frontend would change.
 *
 * The cache is cleared on switch. Identity changes which rows the backend
 * attributes a submission to, so leaving another user's fetched data on screen
 * under a new id would be actively misleading.
 */
export default function UserSwitcher() {
  const client = useQueryClient()
  const [userId, setUserId] = useState(currentUserId)

  function change(event) {
    const next = event.target.value
    setUserId(next)
    setCurrentUserId(next)
    client.clear()
  }

  return (
    <label className="row" style={{ marginBottom: 0, gap: 8 }}>
      <span style={{ color: 'var(--text-muted)', fontWeight: 500, fontSize: '0.85rem' }}>
        X-User-Id
      </span>
      <input
        value={userId}
        onChange={change}
        inputMode="numeric"
        style={{ width: 70 }}
        title="Auth is stubbed: this is the X-User-Id header sent with every request"
      />
    </label>
  )
}
