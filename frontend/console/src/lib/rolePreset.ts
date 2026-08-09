/**
 * rolePreset.ts — the single source of truth for UI capability under the preset role-based access
 * model (Phase 2, the console mirror of the gateway's Phase 1 — see
 * `services/gateway/.../RoutingConfig.java`'s class javadoc for the authoritative capability
 * bundles this file MIRRORS EXACTLY).
 *
 * **Page/nav-hiding here is UX only, never security.** The gateway's `RoleAuthorizationFilter`
 * (keyed on the same capability arrays below) is the real boundary — a bypassed or stale check
 * here only risks showing a link that then 403s at the API, never a wider grant. Never invert that
 * relationship.
 *
 * The 8 roles and their bundles (identical to `RoutingConfig`'s `*_ROLES` arrays):
 *  - **POS**     (till + kitchen)                          — owner, manager, cashier, chef, waitress
 *  - **OPS**     (org-units, outlets, team, promotions,
 *                 loyalty rules, channels, self-order,
 *                 consolidation-group MEMBERSHIP)           — owner, manager
 *  - **REPORTS** (statements, P&L, revenue)                 — owner, manager, accountant
 *  - **FINANCE** (ap, ar, customers, invoices, vendors,
 *                 bank, tax, budgets, assets, deferrals,
 *                 opening-balances, platform-settlements,
 *                 consolidation-group P&L OUTPUT, closes)   — owner, accountant
 *  - **HR**      (employees, expense claims/categories,
 *                 leave, overtime, work-calendar,
 *                 leave-balances)                           — owner, manager, hr
 *  - **PAYROLL** (payroll runs/setup/reports)                — owner, hr
 *
 * `owner` and `manager` are not spelled out per-function below beyond their membership in each
 * array — `owner` ends up in every bundle (as at the gateway), `manager` in every bundle except
 * FINANCE/PAYROLL (also as at the gateway).
 *
 * Every function is a PURE predicate over a role list — callers decide whether to pass the login's
 * base `auth.roles` or the ADR 0049 P3b merged `effectiveRoles(auth.roles, auth.elevatedRoles)`
 * (the same choice `canDashboard`/`canPos` make today: office-style capabilities read the merged
 * set so an elevated device terminal lights them up; `canPos` itself stays on the base roles,
 * since a personal elevation never changes what the OUTLET credential can do at the till).
 */
import { hasAnyRole, type BusinessRole } from './authContext'

const POS_ROLES: BusinessRole[] = ['owner', 'manager', 'cashier', 'chef', 'waitress']
const OPS_ROLES: BusinessRole[] = ['owner', 'manager']
const REPORTS_ROLES: BusinessRole[] = ['owner', 'manager', 'accountant']
const FINANCE_ROLES: BusinessRole[] = ['owner', 'accountant']
const HR_ROLES: BusinessRole[] = ['owner', 'manager', 'hr']
const PAYROLL_ROLES: BusinessRole[] = ['owner', 'hr']

/** The till/kitchen floor — owner, manager, cashier, chef, waitress. */
export function canPos(roles: readonly string[]): boolean {
  return hasAnyRole(roles, ...POS_ROLES)
}

/** Operations — org structure, outlets, team/users, promotions, loyalty rules, channels,
 *  self-order administration, consolidation-group membership — owner, manager. */
export function canOps(roles: readonly string[]): boolean {
  return hasAnyRole(roles, ...OPS_ROLES)
}

/** Financial reports — statements, P&L, revenue — owner, manager, accountant. */
export function canReports(roles: readonly string[]): boolean {
  return hasAnyRole(roles, ...REPORTS_ROLES)
}

/** The detailed back-office books — AP/AR/bank/tax/budgets/assets/deferrals/opening-balances/
 *  platform-settlements/consolidation-group P&L/closes — owner, accountant. */
export function canFinance(roles: readonly string[]): boolean {
  return hasAnyRole(roles, ...FINANCE_ROLES)
}

/** HR — employee records, expense claims/categories, leave, overtime, work calendar, leave
 *  balances — owner, manager, hr. */
export function canHr(roles: readonly string[]): boolean {
  return hasAnyRole(roles, ...HR_ROLES)
}

/** Payroll — runs/setup/reports — owner, hr only (manager is deliberately excluded, matching
 *  the gateway: payroll is not an operations concern). */
export function canPayroll(roles: readonly string[]): boolean {
  return hasAnyRole(roles, ...PAYROLL_ROLES)
}

/**
 * The landing route for a login, by HIGHEST-PRIVILEGE role held (a multi-role login — e.g.
 * `hr` + `accountant` — picks the first match in this priority order, not any particular array
 * order): owner/manager > accountant > hr > chef > cashier > waitress > employee. Pure and role-
 * only (no grant/tier awareness) — callers that also honour per-login page grants / plan tier
 * (App.tsx's `home` cascade) use this as the PREFERRED target and fall back further only when the
 * grant/tier layer hides it.
 */
const HOME_PRIORITY: readonly { role: BusinessRole; path: string }[] = [
  { role: 'owner', path: '/' },
  { role: 'manager', path: '/' },
  { role: 'accountant', path: '/statements/income' },
  { role: 'hr', path: '/people' },
  { role: 'chef', path: '/kitchen' },
  { role: 'cashier', path: '/pos' },
  { role: 'waitress', path: '/pos' },
  { role: 'employee', path: '/me' },
]

export function ROLE_HOME(roles: readonly string[]): string {
  for (const { role, path } of HOME_PRIORITY) {
    if (roles.includes(role)) return path
  }
  // A login with none of the above (should not happen in practice — every invited login gets at
  // least one business role) falls back to the always-available self-service floor.
  return '/me'
}
