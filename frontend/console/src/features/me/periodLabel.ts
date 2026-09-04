/**
 * periodLabel — turns a payslip run's raw `'YYYY-MM'` period key into a localized "Month YYYY"
 * label (e.g. `'2026-07'` → "Juli 2026" / "July 2026"). Shared by the console's own `/me`
 * surfaces (desktop `PayslipsSection`, phone `MePayslipsScreen`) AND the employee app's
 * `PayslipsScreen` (imports this file straight from `@/features/me/periodLabel`, since `@` in
 * the employee package resolves into this console source tree — see employee/vite.config.ts) —
 * one implementation, never re-derived per screen (rule 9: every date through `Intl`, never
 * hand-rolled).
 */

/** 'YYYY-MM' → a localized "Month YYYY" label — falls back to the raw period on a bad input. */
export function periodLabel(period: string, locale: string): string {
  const d = new Date(`${period}-01T00:00:00`)
  if (Number.isNaN(d.getTime())) return period
  return new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' }).format(d)
}
