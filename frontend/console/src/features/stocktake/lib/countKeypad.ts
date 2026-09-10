/**
 * countKeypad.ts — the count sheet's own keypad, as a pure reducer.
 *
 * The count used to be typed into an inline text field with the system keyboard, which on a 412px
 * phone covers ~40% of the screen INCLUDING the row being edited. The count sheet draws a 3×4 pad
 * of its own instead: it knows whether the ingredient admits fractions (kg/liter do, pcs/pack do
 * not), so the decimal key only exists when a decimal can be saved, and a base-unit count can never
 * be typed into an unusable state by the pad alone. (A physical keyboard is routed through the same
 * reducer; paste is checked against the same cap with `countDraftWithinCap`.)
 *
 * The draft is the SHOWN-unit string exactly as it will be parsed by `parseShownQtyInput` — either
 * separator is accepted there, so the pad offers the operator's locale separator and stores it as
 * is, never normalising.
 */

/**
 * The cap is on the BASE magnitude, not on the typed string: a count may hold at most this many
 * base-unit digits (999 999 999 g / ml / pcs). The server stores an `int`, and the live money
 * preview multiplies the base quantity by a per-base unit cost — both need the base to stay well
 * inside range. Anything longer is a slip, not a stock level.
 */
export const COUNT_MAX_BASE_DIGITS = 9

/** A display unit sits exactly 1000× above its base (inventory/lib/units.ts), i.e. 3 digits. */
const DISPLAY_FRACTION_DIGITS = 3

export type CountKey =
  '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' | ',' | '.' | 'backspace'

/** The decimal separator the operator's locale writes — "," under id-ID, "." under en-US. */
export function decimalSeparatorOf(locale: string): ',' | '.' {
  const part = new Intl.NumberFormat(locale).formatToParts(1.1).find((p) => p.type === 'decimal')
  return part?.value === ',' ? ',' : '.'
}

function splitDraft(draft: string): { whole: string; fraction: string | null } {
  const at = draft.search(/[.,]/)
  if (at < 0) return { whole: draft, fraction: null }
  return { whole: draft.slice(0, at), fraction: draft.slice(at + 1) }
}

/**
 * True when the draft's BASE magnitude fits the cap. For a whole-unit item that is the digit count
 * itself; for a display unit (kg/liter) the whole part may hold three digits fewer — each shown
 * unit is a thousand base units — and the fraction at most three, which is all `toBaseQty` keeps.
 */
export function countDraftWithinCap(draft: string, allowsFraction: boolean): boolean {
  const { whole, fraction } = splitDraft(draft)
  const wholeDigits = whole.replace(/[^\d]/g, '').length
  if (!allowsFraction) return fraction == null && wholeDigits <= COUNT_MAX_BASE_DIGITS
  const fractionDigits = (fraction ?? '').replace(/[^\d]/g, '').length
  return (
    wholeDigits <= COUNT_MAX_BASE_DIGITS - DISPLAY_FRACTION_DIGITS &&
    fractionDigits <= DISPLAY_FRACTION_DIGITS
  )
}

/**
 * One key pressed over the current draft. Rules:
 *  - backspace drops the last character;
 *  - a separator is refused for a whole-unit ingredient, and never appears twice — on an empty
 *    draft it starts "0," rather than a bare ",", which would not parse;
 *  - a digit replaces a lone leading "0" (so "0" then "5" reads "5", not "05") and is refused once
 *    the draft is at the cap (`countDraftWithinCap`).
 */
export function applyCountKey(draft: string, key: CountKey, allowsFraction: boolean): string {
  if (key === 'backspace') return draft.slice(0, -1)
  if (key === ',' || key === '.') {
    if (!allowsFraction) return draft
    if (/[.,]/.test(draft)) return draft
    return draft === '' ? `0${key}` : `${draft}${key}`
  }
  const next = draft === '0' ? key : `${draft}${key}`
  return countDraftWithinCap(next, allowsFraction) ? next : draft
}
