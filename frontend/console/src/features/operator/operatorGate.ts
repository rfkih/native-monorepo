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
