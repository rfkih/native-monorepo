/**
 * Compose the FULL company-scoped login id an owner/manager hands to an employee.
 *
 * ADR 0054: an employee signs in as `<companyCode>.<local>` (e.g. `dg7chf.leha`); the owner signs
 * in with their email. The org-service team list (`/api/v1/users`) STRIPS the `<companyCode>.`
 * prefix off scoped logins for a clean in-app display — but every surface that hands credentials
 * over (the create-login success screen, the employee detail drawer, the employees list) needs the
 * WHOLE id, since that is exactly what the employee types at the login box. Showing the bare local
 * (`leha`) there is the bug this fixes.
 *
 * Idempotent + graceful, so it is safe regardless of whether the server sent a stripped local or an
 * already-full value:
 *   - an email (owner) is returned as-is — no company prefix,
 *   - an already-prefixed value is returned unchanged — never double-prefixes, and
 *   - a stripped local with no company code available falls back to the local (best effort).
 */
export function displayLoginId(
  username: string | null | undefined,
  companyCode: string | null | undefined,
): string {
  const local = (username ?? '').trim()
  if (!local) return ''
  if (local.includes('@')) return local // owner signs in with email — no company prefix
  const code = (companyCode ?? '').trim()
  if (!code) return local // no code available — best effort (server carries it on current builds)
  return local.startsWith(`${code}.`) ? local : `${code}.${local}`
}
