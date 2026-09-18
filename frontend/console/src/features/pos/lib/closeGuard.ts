/**
 * closeGuard.ts — the register-close confirm guard (owner request). Pure logic (no react-query / no
 * DOM) so it is unit-testable in isolation — the registerFloat.ts / registerGate.ts idiom.
 *
 * When the cashier submits a drawer count that doesn't match the system's expected cash, the close
 * pauses for an "are you sure?" confirm — a fat-finger safety net. The server stays authoritative
 * (it recomputes and records whatever is submitted); this only decides whether to show the step.
 */

/**
 * True when the close should pause for confirmation: expected cash is KNOWN (the live preview
 * loaded) AND the counted drawer differs from it. An unknown expected (`null` — the preview is still
 * loading or errored) never blocks the close; an exact match closes straight through. Both amounts
 * are minor units in the same currency (rule 8), so a plain `!==` is the whole comparison.
 */
export function needsCountConfirmation(
  expectedCashMinor: number | null,
  countedCashMinor: number,
): boolean {
  return expectedCashMinor != null && countedCashMinor !== expectedCashMinor
}

/**
 * ADR 0086 — the register cannot close while any bill at the outlet is OPEN; each one is paid or
 * cancelled by a person first, never swept by the close. True when the live preview says bills are
 * open. An unknown count (`null`/`undefined` — the preview is still loading or errored) never
 * blocks: the server re-counts under its lock and refuses with 409 `register-session-open-bills`,
 * which is the backstop — the same philosophy as {@link needsCountConfirmation}.
 */
export function closeBlockedByOpenBills(openBillCount: number | null | undefined): boolean {
  return (openBillCount ?? 0) > 0
}
