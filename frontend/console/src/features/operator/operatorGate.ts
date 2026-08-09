/**
 * operatorGate.ts — pure decision core for the "sign in before ringing" gate (ADR 0049 P3b),
 * mirroring `features/pos/lib/registerGate.ts`'s shape. On a Business-app device terminal, no
 * active operator session means no sale: FAIL CLOSED, matching the eventual P4 backend guard
 * (`409 operator-required`). A normal `user` login never gates — the whole flow is a no-op there.
 *
 * One call site per POS surface (Pos.tsx / ServicePos.tsx): the pay/ring handler (and the KOT/send
 * handler, which also attributes a sale) checks this FIRST, before any other gate (e.g. the
 * restaurant register-open gate) — a cashier identifies themselves before anything else happens.
 */
export function operatorSignInRequired(isDeviceTerminal: boolean, operator: unknown): boolean {
  return isDeviceTerminal && operator == null
}

/**
 * Which step the operator sign-in sheet shows for a picked employee — the pure, unit-testable core
 * of the till's PIN/enrollment gate (ADR 0049), so the security-critical branch can't silently drift
 * in a component refactor (code review W1).
 *
 *  - `no-pin` — a no-PIN outlet: pick and go, no keypad.
 *  - `enter-pin` — the employee already has a PIN (`hasPin === true`) OR `hasPin` is unknown
 *    (`undefined` — an older roster payload / deploy-order skew): show the enter-PIN keypad rather
 *    than forcing enrollment (code review W2 — fail to the prior behavior, never block everyone).
 *  - `manager-required` — a require-PIN outlet's PIN-LESS employee (`hasPin === false`) with NO
 *    manager present: the first PIN may only be set with an elevated owner/manager (`managerElevated`
 *    is derived from `auth.elevatedRoles`, NOT the merged effectiveRoles — the till's own role is
 *    cashier). Shows the "a manager must sign in" step.
 *  - `set-pin` — a require-PIN, PIN-less employee WITH a manager present: show the Set-PIN step.
 */
export type OperatorSheetStep = 'no-pin' | 'enter-pin' | 'manager-required' | 'set-pin'

export function operatorSheetStep(params: {
  requirePin: boolean
  hasPin: boolean | undefined
  managerElevated: boolean
}): OperatorSheetStep {
  if (!params.requirePin) return 'no-pin'
  if (params.hasPin !== false) return 'enter-pin'
  return params.managerElevated ? 'set-pin' : 'manager-required'
}
