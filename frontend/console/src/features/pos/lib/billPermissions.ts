/**
 * billPermissions — the open-bill lockdown policy (owner rule 2026-08-31), as pure functions so
 * the UI gating is unit-testable and stays in lock-step with the server guards
 * (restaurant-service BillWriter.cancelBill/removeLine — the real enforcement; hiding buttons here
 * is UX, not security).
 *
 * Policy: once a bill holds items its flow must end in payment — a cashier can neither cancel it
 * nor trim its lines. Only an EMPTY bill (wrong table opened) may be cancelled by anyone. A bill
 * with PAID lines (partial split-check) is uncancellable for EVERY role — the recorded sales would
 * be stranded (server hardening: 409 bill-has-paid-lines) — so the button is not offered at all.
 * `canVoid` = the login (incl. device-terminal elevation) holds owner/manager, computed in Pos.tsx
 * beside the manual-discount gate.
 */

/** Cancelling: no paid lines ever; then owner/manager always, anyone when the bill is empty. */
export function canCancelBill(canVoid: boolean, lineCount: number, hasPaidLines: boolean): boolean {
  if (hasPaidLines) return false
  return canVoid || lineCount === 0
}

/** True when the cancel spot should EXPLAIN itself instead (role-blocked cashier, no paid lines —
 *  a partially-paid bill shows nothing: cancel is impossible for every role by design). */
export function showCancelNeedsManager(
  canVoid: boolean,
  lineCount: number,
  hasPaidLines: boolean,
): boolean {
  return !canVoid && lineCount > 0 && !hasPaidLines
}

/** Removing/decrementing lines: owner/manager only. */
export function canRemoveBillLines(canVoid: boolean): boolean {
  return canVoid
}
