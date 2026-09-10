/**
 * stocktakeMarks.ts — the "checked" state of an opname row, and the two doors out of "unchecked".
 *
 * Every count field arrives pre-filled with the system quantity, so a row nobody looked at and a
 * row someone counted and found equal send the SAME number to the ledger and used to look the same
 * on screen. On a sixteen-item count that turned the opname into a rubber stamp. "Checked" is a
 * status of its own now: a row starts `pending` and leaves it either by one tap on its check mark
 * (verified — matches the system) or by a typed count that differs (changed). It is purely a
 * client-side aid — the submit payload is unchanged, one `countedQty` per ingredient, and a
 * `pending` row still goes out at its system quantity. The footer says how many did.
 *
 * Pure (no react / no DOM), the same shape as ./stocktakeDraft. Quantities are BASE units.
 */

export type StocktakeRowState = 'pending' | 'verified' | 'changed' | 'invalid'

export interface StocktakeMarks {
  /**
   * The operator's typed counts, keyed by ingredient id, in the SHOWN unit exactly as typed. Only
   * rows whose count differs from the system quantity live here — a count typed back to the
   * system figure is stored as a verification instead (see `saveStocktakeCount`).
   */
  overrides: Record<string, string>
  /** Rows the operator confirmed equal to the system quantity. */
  verified: Record<string, true>
}

export const EMPTY_MARKS: StocktakeMarks = { overrides: {}, verified: {} }

/** A row's state from its parsed count (null = unparseable), its system quantity and its mark. */
export function stocktakeRowState(
  countedQty: number | null,
  systemQty: number,
  verified: boolean,
): StocktakeRowState {
  if (countedQty == null) return 'invalid'
  if (countedQty !== systemQty) return 'changed'
  return verified ? 'verified' : 'pending'
}

export interface StocktakeProgress {
  total: number
  /** Rows out of `pending`: verified or changed. Invalid rows are neither. */
  resolved: number
  pending: number
  invalid: number
}

/** The header strip's numbers — real progress, not "fields with a value in them". */
export function summarizeStocktakeProgress(states: StocktakeRowState[]): StocktakeProgress {
  let resolved = 0
  let pending = 0
  let invalid = 0
  for (const state of states) {
    if (state === 'pending') pending += 1
    else if (state === 'invalid') invalid += 1
    else resolved += 1
  }
  return { total: states.length, resolved, pending, invalid }
}

/**
 * The check mark tapped. It means three things, by the row's state:
 *  - pending  → verified (the physical count matches the system).
 *  - changed / invalid → back to the system quantity AND verified — the typed count is dropped.
 *    The count sheet's "same as system" does exactly the same.
 *  - verified → pending again (unmark).
 */
export function toggleStocktakeMark(
  marks: StocktakeMarks,
  ingredientId: string,
  state: StocktakeRowState,
): StocktakeMarks {
  const overrides = { ...marks.overrides }
  const verified = { ...marks.verified }
  if (state === 'verified') {
    delete verified[ingredientId]
  } else {
    delete overrides[ingredientId]
    verified[ingredientId] = true
  }
  return { overrides, verified }
}

/**
 * The count sheet saved. Typing the system figure back is still an inspection — it counts as
 * verified, and no override is kept for it; anything else is kept as typed and un-verifies the row.
 */
export function saveStocktakeCount(
  marks: StocktakeMarks,
  ingredientId: string,
  raw: string,
  countedQty: number | null,
  systemQty: number,
): StocktakeMarks {
  const overrides = { ...marks.overrides }
  const verified = { ...marks.verified }
  if (countedQty != null && countedQty === systemQty) {
    delete overrides[ingredientId]
    verified[ingredientId] = true
  } else {
    overrides[ingredientId] = raw
    delete verified[ingredientId]
  }
  return { overrides, verified }
}

/** Rows the operator has worked on — what the discard confirm names, and what makes a count dirty. */
export function workedRowCount(marks: StocktakeMarks): number {
  return new Set([...Object.keys(marks.overrides), ...Object.keys(marks.verified)]).size
}
