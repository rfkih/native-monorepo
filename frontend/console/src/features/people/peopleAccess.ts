/**
 * Pure helpers behind the standalone People page (`PeoplePage.tsx`) — the last gap in the preset
 * role-based access model (ADR 0052): an `hr`-alone login has no `owner`/`manager` role, so it must
 * never land on `OrgUnitDetail` (an OPS management screen that fires `useTeam`/`useUnitUsers`,
 * both org-service `/api/v1/users/**`, OPS-only). Kept separate from the component (no React) so
 * the capability/business-unit logic is trivially unit-testable, mirroring `lib/rolePreset.ts`'s
 * own pure-predicate style.
 */
import type { OrgUnit } from '@/features/org/api'
import { canHr, canPayroll } from '@/lib/rolePreset'

export type PeopleTabKey = 'employees' | 'attendance' | 'payroll'

/**
 * The tabs a login may see on the People page: Employees + Attendance whenever HR-capable;
 * Payroll ADDITIONALLY only when payroll-capable too (owner/hr — a manager is deliberately
 * excluded, matching the gateway's PAYROLL bundle exactly, same as `orgHub`'s nav item gating in
 * `navGroups.ts`). Returns an empty list for a login without HR at all — defensive only: the
 * `/people` ROUTE itself is already `canHr`-gated in `App.tsx`, so this should never actually
 * render empty in practice.
 */
export function visiblePeopleTabs(roles: readonly string[]): PeopleTabKey[] {
  if (!canHr(roles)) return []
  return canPayroll(roles) ? ['employees', 'attendance', 'payroll'] : ['employees', 'attendance']
}

/** Every `BUSINESS_UNIT` row from the flat org-units list, in the order the server returned them. */
export function businessUnitsOf(units: readonly OrgUnit[]): OrgUnit[] {
  return units.filter((u) => u.type === 'BUSINESS_UNIT')
}

/** A business unit's direct `OUTLET` children — the same shape `OrgUnitDetail` passes its tabs. */
export function childOutletsOf(units: readonly OrgUnit[], unitId: string): OrgUnit[] {
  return units.filter((u) => u.parentId === unitId && u.type === 'OUTLET')
}

/**
 * The business unit the picker should default to: the session's own business unit when it IS one
 * of the company's business units, else the first business unit (server order), else `null` (no
 * business unit exists yet — a fresh/mid-onboarding tenant).
 */
export function defaultBusinessUnitId(
  businessUnits: readonly OrgUnit[],
  sessionBusinessId: string | null,
): string | null {
  if (sessionBusinessId && businessUnits.some((u) => u.id === sessionBusinessId)) {
    return sessionBusinessId
  }
  return businessUnits[0]?.id ?? null
}
