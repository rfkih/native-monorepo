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
  if (pathname === '/ingredients') return false
  if (pathname === '/onboarding' || pathname.startsWith('/onboarding/')) return false
  return true
}
