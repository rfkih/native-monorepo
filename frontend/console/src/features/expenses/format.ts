/**
 * Plain (non-component) expenses helpers — mirrors features/ar/format.ts / features/ap/format.ts /
 * features/bank/format.ts (each finance-adjacent feature keeps its own tiny formatter module so a
 * component file only exports components, react-refresh/only-export-components clean).
 */

/** Localized short date, e.g. `Jul 28, 2026` / `28 Jul 2026` — falls back to an em dash. */
export function formatDate(iso: string | null | undefined, locale: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(d)
}
