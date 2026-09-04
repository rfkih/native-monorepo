/**
 * Plain (non-component) inventory-method helpers — split out so InventoryMethodSettings.tsx only
 * exports components (keeps react-refresh/only-export-components clean; mirrors why ap/format.ts,
 * bank/format.ts, and tax/format.ts each keep their own small formatter beside their feature).
 */

/** Localized date + time, e.g. `Aug 17, 2026, 3:04 PM` / `17 Agu 2026 15.04` — for the
 *  `activatedAt` server instant (a moment, not a plain calendar date). Falls back to an em dash. */
export function formatActivatedAt(iso: string | null | undefined, locale: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}
