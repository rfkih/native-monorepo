/**
 * billLineGroups.ts — groups identical UNPAID bill lines into one display row so a tap-per-item
 * POS can present "qty × item" with a stepper, without any backend change (BillWriter never
 * merges lines server-side — see the −/+ stepper plan). PAID lines and split mode are untouched;
 * callers only use this for the non-split, unpaid display.
 */
import type { BillLineModifierResponse, BillLineResponse } from '../billsApi'

export interface BillLineGroup {
  /** Stable identity for this group: menuItemId + sorted modifier option ids. */
  key: string
  menuItemId: string
  nameSnapshot: string
  optionIds: string[]
  modifiers: BillLineModifierResponse[]
  unitPriceMinor: number
  modifierDeltaMinor: number
  /** Every underlying bill-line id in this group, in insertion order. */
  lineIds: string[]
  /** Number of underlying lines (== lineIds.length). The stepper is line-granular — each "−"
   *  removes ONE line — so the displayed qty must be the line COUNT, not a sum of line.qty (every
   *  frontend append is qty 1; deriving from the count stays correct even if a qty>1 line appears). */
  qty: number
  /** Sum of each underlying line's lineTotalMinor. */
  lineTotalMinor: number
}

function groupKey(line: BillLineResponse): string {
  const optionIds = line.modifiers.map((m) => m.optionId).sort()
  return `${line.menuItemId}|${optionIds.join(',')}`
}

/** Groups unpaid lines only (`!line.paid`); preserves first-seen insertion order. */
export function groupUnpaidLines(lines: BillLineResponse[]): BillLineGroup[] {
  const groups = new Map<string, BillLineGroup>()

  for (const line of lines) {
    if (line.paid) continue
    const key = groupKey(line)
    const existing = groups.get(key)
    if (existing) {
      existing.lineIds.push(line.id)
      existing.qty += 1
      existing.lineTotalMinor += line.lineTotalMinor
    } else {
      groups.set(key, {
        key,
        menuItemId: line.menuItemId,
        nameSnapshot: line.nameSnapshot,
        optionIds: line.modifiers.map((m) => m.optionId),
        modifiers: line.modifiers,
        unitPriceMinor: line.unitPriceMinor,
        modifierDeltaMinor: line.modifierDeltaMinor,
        lineIds: [line.id],
        qty: 1,
        lineTotalMinor: line.lineTotalMinor,
      })
    }
  }

  return Array.from(groups.values())
}
