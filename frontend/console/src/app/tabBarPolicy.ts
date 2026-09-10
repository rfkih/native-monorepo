/**
 * Pure mount policy for the phone bottom navigation — its own module (not in the gate
 * component file) so it stays unit-testable and fast-refresh-safe.
 *
 * False on every full-screen till surface (each carries its own chrome — ADR 0043) and on
 * onboarding (a deliberate no-escape wizard).
 */
export function shouldMountTabBar(pathname: string): boolean {
  if (pathname === '/pos' || pathname.startsWith('/pos/')) return false
  if (pathname === '/menu' || pathname === '/catalog' || pathname === '/kitchen') return false
  if (pathname === '/inventory' || pathname.startsWith('/inventory/')) return false
  if (pathname === '/onboarding' || pathname.startsWith('/onboarding/')) return false
  return true
}

/**
 * The office bar's two MIDDLE slots (home and More are fixed), in candidate order.
 *
 * The gate used to compute only what to HIDE, with nothing to backfill. That is fine for an
 * owner/manager — Reports and Team fill both slots — but an accountant lost both: Reports fails its
 * own "not the page you are already on" test because `/statements/income` IS their home, and Team
 * needs ops. They got two tabs stretched across four columns while receivables and payables, their
 * daily work, sat two taps deep inside More.
 *
 * So the slots are filled from an ordered candidate list instead: a persona that cannot reach the
 * earlier candidates falls through to the next thing its OWN role bundle opens. This widens
 * nothing — the caller passes the same role ∧ grant ∧ tier result each route already enforces, and
 * a candidate whose route IS the home tab is dropped rather than duplicated.
 */
export const OFFICE_TAB_ORDER = ['reports', 'team', 'ar', 'ap'] as const

export type OfficeTabKey = (typeof OFFICE_TAB_ORDER)[number]

/** Where each candidate points — kept here so the "never duplicate home" rule is testable. */
export const OFFICE_TAB_ROUTES: Record<OfficeTabKey, string> = {
  reports: '/statements/income',
  team: '/team',
  ar: '/ar/aging',
  ap: '/ap/aging',
}

/** Home and More are fixed; this is what is left. */
const MIDDLE_SLOTS = 2

export function officeMiddleTabs(
  eligible: Record<OfficeTabKey, boolean>,
  home: string,
): OfficeTabKey[] {
  return OFFICE_TAB_ORDER.filter((key) => eligible[key] && OFFICE_TAB_ROUTES[key] !== home).slice(
    0,
    MIDDLE_SLOTS,
  )
}
