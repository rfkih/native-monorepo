/**
 * moneyInput.ts — a cost typed in MAJOR units of the company base currency, as a free-text field
 * (the number keyboard silently swallowed a comma; a text field with `inputMode="decimal"` has no
 * browser-side filter of its own, so the filtering happens here).
 *
 * Rule 8: the result is integer minor units. A zero-exponent currency (IDR) has no fraction, so any
 * separator can only be the Indonesian grouping habit ("25.000") — it is refused rather than
 * mis-scaled to 25 (the features/expenses/amount.ts E6/E7 W2 lesson, and what
 * `activationForm.ts`'s opening-value parser does). A two-exponent currency accepts ONE separator,
 * either kind, as the decimal point.
 */
import { isoMinorExponent } from '@/lib/money'

/** Strips what `parseMoneyInput` would refuse, keystroke by keystroke: digits, plus one
 *  separator for a currency that has a fraction at all. An empty result is a cleared field. */
export function sanitizeMoneyInput(raw: string, currency: string): string {
  const kept = raw.replace(/[^\d.,]/g, '')
  if (isoMinorExponent(currency) === 0) return kept.replace(/[.,]/g, '')
  const at = kept.search(/[.,]/)
  if (at < 0) return kept
  return kept.slice(0, at + 1) + kept.slice(at + 1).replace(/[.,]/g, '')
}

/**
 * Major-unit text → integer minor units, or `null` when blank or unparsable. Zero is a legitimate
 * figure. Never `0` for garbage: a field that cannot be read must block the save, not save a free
 * ingredient over a real cost.
 */
export function parseMoneyInput(raw: string, currency: string): number | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  const exponent = isoMinorExponent(currency)
  if (exponent === 0 && /[.,]/.test(trimmed)) return null
  if (!/^\d*[.,]?\d*$/.test(trimmed)) return null
  const major = Number(trimmed.replace(',', '.'))
  if (!Number.isFinite(major) || major < 0) return null
  return Math.round(major * 10 ** exponent)
}
