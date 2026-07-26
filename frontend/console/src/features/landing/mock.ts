/**
 * Illustrative demo figures + locale-aware money formatting for the landing-page mockups.
 * Kept in a plain module (no components) so the artwork file stays Fast-Refresh friendly.
 */

// IDR minor units — the base currency the demo company keeps its books in.
export const MOCK_REVENUE = 482_400_000
export const MOCK_EXPENSE = 331_900_000
export const MOCK_NET = MOCK_REVENUE - MOCK_EXPENSE

/** Format IDR minor units, locale-aware — never hand-concatenated (CLAUDE.md rule 9). */
export function money(minor: number, locale: string, compact = false): string {
  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: 'IDR',
      maximumFractionDigits: 0,
      notation: compact ? 'compact' : 'standard',
    }).format(minor)
  } catch {
    return String(minor)
  }
}
