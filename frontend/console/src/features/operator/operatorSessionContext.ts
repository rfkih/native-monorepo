import { createContext, useContext } from 'react'
import type { OperatorSessionData } from './operatorSessionStore'

/**
 * The active operator, minus the raw token — the till only ever DISPLAYS name + role (rule 6); the
 * token itself lives only inside the provider + `lib/api.ts`'s module var, never in a component.
 */
export type OperatorInfo = Pick<OperatorSessionData, 'displayName' | 'role'>

export interface OperatorSessionValue {
  /** The signed-in operator, or null when no one has signed in yet (or the session expired / was
   * signed out). Always null on a normal `user` login's till — nothing ever calls `signIn` there. */
  operator: OperatorInfo | null
  /** Verifies `(employeeId, pin)` at `businessId` and, on success, signs the operator in (persists
   * + arms `X-Operator-Session` on every subsequent request). `pin` is OPTIONAL (ADR 0049 P3d) —
   * omit it entirely at a no-PIN outlet; the sheet decides which to send based on the outlet's
   * policy, never this context. Throws on failure — the caller (the PIN sheet) maps the thrown
   * error to a friendly message. */
  signIn: (businessId: string, employeeId: string, pin?: string) => Promise<OperatorInfo>
  /** Clears the operator (shift change / the till-menu "Sign out operator" item) — back to the PIN
   * prompt on the next sale. */
  signOut: () => void
}

/**
 * Hook-only module (no component export) so `OperatorSessionProvider.tsx` can stay Fast-Refresh
 * clean — mirrors `lib/authContext.ts`'s split from `lib/auth.tsx`.
 */
export const OperatorSessionContext = createContext<OperatorSessionValue | null>(null)

export function useOperatorSession(): OperatorSessionValue {
  const ctx = useContext(OperatorSessionContext)
  if (!ctx) throw new Error('useOperatorSession must be used within an OperatorSessionProvider')
  return ctx
}
