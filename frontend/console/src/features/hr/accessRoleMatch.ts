/**
 * accessRoleMatch.ts — pure helpers behind the HR employee drawer's "App access" section (Phase 1
 * of the HR-job-title vs. app-access-role visibility fix).
 *
 * Native has two unrelated "role" concepts an owner easily confuses:
 *  - the HR job title (`assignment.role`, employee-service — FREE TEXT, e.g. "manager"), purely
 *    descriptive, shown as `{role} · {orgUnit}` on the HR/People pages, and grants NOTHING;
 *  - the app-access role (the Keycloak realm role — owner/manager/accountant/hr/chef/cashier/
 *    waitress/employee) the gateway actually enforces, set via the Team page.
 *
 * These helpers detect the one case worth flagging: the job title happens to SPELL one of the 8
 * real access-role names, but the linked login doesn't actually hold it — exactly the real bug
 * report (job title set to "manager", access role stuck at "cashier", so the person only ever saw
 * the till).
 *
 * Kept React-free (no hooks, no components) so it is trivially unit-testable — mirrors
 * `features/people/peopleAccess.ts`'s own pure-predicate style. Reuses `BUSINESS_ROLES` from
 * `lib/authContext.ts` (the single source of truth for the 8 access roles — do not redefine the
 * list here).
 */
import { BUSINESS_ROLES, type BusinessRole } from '@/lib/authContext'

/**
 * The HR job title, exactly as an access-role NAME would spell it (trimmed, lower-cased), or
 * `null` when it isn't one of the 8 access roles at all — the overwhelmingly common case, since
 * most job titles ("barista", "shift_lead", "host"...) are not access-role names.
 */
export function impliedAccessRole(jobTitle: string | null | undefined): BusinessRole | null {
  if (!jobTitle) return null
  const normalized = jobTitle.trim().toLowerCase()
  return (BUSINESS_ROLES as readonly string[]).includes(normalized)
    ? (normalized as BusinessRole)
    : null
}

/**
 * True when the job title implies an access role the login does NOT currently hold — the exact
 * mismatch worth warning about. False whenever there is no implied role at all (most job titles)
 * or the login already holds it (nothing to fix).
 */
export function hasAccessRoleMismatch(
  jobTitle: string | null | undefined,
  memberRoles: readonly string[],
): boolean {
  const implied = impliedAccessRole(jobTitle)
  return implied !== null && !memberRoles.includes(implied)
}

/**
 * De-duplicated union, order-preserving on first occurrence — used to build the ADDITIVE role set
 * sent to `useUpdateMember` (existing roles plus the newly granted one). `useUpdateMember`'s PATCH
 * REPLACES the whole role set, so the caller must always pass the union, never just the new role.
 */
export function uniqueBusinessRoles(roles: readonly string[]): string[] {
  return [...new Set(roles)]
}
