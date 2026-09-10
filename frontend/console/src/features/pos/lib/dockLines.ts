/**
 * dockLines — the BILL DOCK's policy, as pure functions (ADR 0079 / Native Till Android v2).
 *
 * The phone till no longer keeps the bill inside a sheet you open: it sits on screen as a dock
 * that is always attached, peeking at the tail of the ticket and expanding to the full list. Two
 * decisions that dock makes are worth pinning down away from JSX, because both are easy to get
 * subtly wrong and neither is visible in a screenshot:
 *
 *   1. WHICH lines the peek shows — the newest UNPAID ones. A partially-paid split check would
 *      otherwise peek at settled history while the cashier is still ringing.
 *   2. WHICH action chips the expanded dock offers — the gates differ between the walk-in cart and
 *      an open bill, and they mirror rules that already exist elsewhere (ADR 0026/0027 scope the
 *      coupon + member fields to the walk-in cart; split needs two unpaid lines to mean anything).
 *
 * Formatting stays with the callers: everything here is structure, never a string a person reads.
 */

/** The dock's action chips, in the order the expanded dock renders them. */
export type DockActionKey = 'split' | 'discount' | 'member' | 'attachments'

/** Anything the dock can list — the walk-in cart's local lines and a bill's server lines both
 *  satisfy it, which is why the dock takes one shape from two very different callers. */
interface PayableLine {
  paid: boolean
}

/**
 * The `n` newest UNPAID lines, oldest-first — what the collapsed dock peeks at.
 *
 * Paid lines are dropped rather than dimmed: the peek is ~1.5 rows tall, and a settled line there
 * would push the line the cashier just tapped off the bottom of a dock that exists to confirm it.
 * The expanded dock still shows the whole ticket, paid rows included.
 */
export function peekLines<T extends PayableLine>(lines: readonly T[], n = 3): T[] {
  if (n <= 0) return []
  return lines.filter((l) => !l.paid).slice(-n)
}

/**
 * The i18n key for the dock's due-figure label. Split mode wins over a partial payment: while the
 * cashier is ticking rows, the figure below is the SELECTED subset, not what the table still owes.
 */
export function dueLabelKey({
  splitMode,
  hasPaidLines,
}: {
  splitMode: boolean
  hasPaidLines: boolean
}): string {
  if (splitMode) return 'bills.splitSelected'
  if (hasPaidLines) return 'posShell.dock.dueRemaining'
  return 'pos.total'
}

/**
 * Which action chips the expanded dock offers.
 *
 * - `split` — bills only, and only with two unpaid lines to split. A split check charges an
 *   explicit subset of bill LINE IDS; the walk-in cart has no server-side lines to name, so the
 *   chip would be an affordance for something that cannot happen. (The phone sheet it replaces was
 *   bill-only by construction, which is why the rule was never written down before.)
 * - `discount` — owner/manager only (ADR 0026 §5, same gate as the manual-discount field) and
 *   walk-in only, because that is where the field lives.
 * - `member` — walk-in only: coupons and loyalty are scoped to the cart (ADR 0026/0027).
 * - `attachments` — bill only (ADR 0063 hangs private media off a bill, not off a cart).
 *
 * The mockup also draws a "Park" chip, but its only job there is to open the parked tray — which
 * the header's Incoming button already does. A second door to the same room is not offered here.
 *
 * `canRemoveLines` is deliberately NOT a gate: it controls the per-row minus stepper, not a chip.
 */
export function dockActions({
  isBill,
  unpaidCount,
  canManualDiscount,
}: {
  isBill: boolean
  unpaidCount: number
  canManualDiscount: boolean
}): DockActionKey[] {
  const keys: DockActionKey[] = []
  if (isBill && unpaidCount >= 2) keys.push('split')
  if (!isBill && canManualDiscount) keys.push('discount')
  if (!isBill) keys.push('member')
  if (isBill) keys.push('attachments')
  return keys
}
