/**
 * Employees tab — the Odoo-style HR list scoped to the current unit. Reused by both the org-unit
 * hub (`OrgUnitDetail`, owner/manager) and the standalone People page (`PeoplePage`, ADR 0052 —
 * owner/manager/hr). The BU rollup is CLIENT-computed: a business unit's scope is [itself,
 * ...its child outlets] from the org tree the hub already has (employee-service's projection holds
 * no parent_id). Employees are HR RECORDS, deliberately separate from the Team page's login users.
 * No PII renders here — the list endpoint never returns NIK / bank account / amounts.
 *
 * `canManageLogins` (default `true`) gates every affordance that touches org-service's OPS-only
 * `/api/v1/users/**` (creating a console login) so an hr-alone login sees employee records without
 * ever firing — or 403ing on — that call; see the prop's own doc below and
 * `EmployeeDetailDrawer`'s file header for the rest of the split.
 */

import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, Plus, Search, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { ErrorDetails } from '@/components/ErrorDetails'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { TextInput } from '@/components/ui/Field'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import type { OrgUnit } from '@/features/org/api'
import { useTeam, type TeamMember } from '@/features/team/api'
import { useSession } from '@/lib/session'
import { cn } from '@/lib/cn'
import { hasAccessRoleMismatch } from './accessRoleMatch'
import { useEmployees, type EmployeeListRow } from './api'
import { isActiveAssignment } from './assignments'
import { CreateLoginDialog } from './CreateLoginDialog'
import { EmployeeDetailDrawer } from './EmployeeDetailDrawer'
import { OperatorPinDialog } from './OperatorPinDialog'
import {
  AssignDialog,
  AssignExistingDialog,
  CompensationDialog,
  EmployeeFormDialog,
  EndAssignmentDialog,
  TerminateDialog,
} from './parts'

/** Child dialogs carry `fromDetail` so closing them returns to the Kelola panel they came from —
 *  the panel is the hub (Native Console Web design), the dialogs are its single-decision leaves. */
type DialogState =
  | { kind: 'create' }
  | { kind: 'edit'; employee: EmployeeListRow; fromDetail?: boolean }
  | { kind: 'assign'; employee: EmployeeListRow; fromDetail?: boolean }
  | { kind: 'endAssignment'; employee: EmployeeListRow; fromDetail?: boolean }
  | { kind: 'compensation'; employee: EmployeeListRow; fromDetail?: boolean }
  | { kind: 'terminate'; employee: EmployeeListRow; fromDetail?: boolean }
  | { kind: 'createLogin'; employee: EmployeeListRow; fromDetail?: boolean }
  | { kind: 'operatorPin'; employee: EmployeeListRow; fromDetail?: boolean }
  | { kind: 'detail'; employeeId: string }
  | { kind: 'assignExisting' }

/** One employee, grouped from their (possibly several) current-assignment rows. */
export interface GroupedEmployee {
  employeeId: string
  fullName: string
  status: EmployeeListRow['status']
  hasCompensation: boolean
  userId: string | null
  rows: EmployeeListRow[]
}

export function EmployeesTab({
  unit,
  childOutlets,
  units,
  companyId,
  actor,
  baseCurrency,
  canManageLogins = true,
}: {
  unit: OrgUnit
  childOutlets: OrgUnit[]
  units: OrgUnit[]
  companyId: string
  actor: string
  baseCurrency: string
  /**
   * Whether this login may create/reset/remove a console LOGIN for an employee here (org-service
   * `/api/v1/users/**`, OPS-only — owner/manager). Defaults to `true`, preserving `OrgUnitDetail`'s
   * historical owner/manager-only usage unchanged; the standalone People page (`PeoplePage.tsx`,
   * reachable by `hr` too) passes `canOps(roles)` so an hr-alone login sees employee RECORDS
   * without ever rendering — let alone firing — that OPS-gated affordance (a forbidden call is a
   * console error even if the button is merely hidden, ADR 0052).
   */
  canManageLogins?: boolean
}) {
  const { t } = useTranslation()
  const [dialog, setDialog] = useState<DialogState | null>(null)

  // Closing a child dialog returns to the Kelola panel when it was opened from there.
  const closeDialog = () =>
    setDialog((d) =>
      d && 'fromDetail' in d && d.fromDetail
        ? { kind: 'detail', employeeId: d.employee.employeeId }
        : null,
    )

  // BU scope = the unit + its child outlets; an outlet scopes to itself only.
  const unitIds = useMemo(
    () =>
      unit.type === 'BUSINESS_UNIT' ? [unit.id, ...childOutlets.map((o) => o.id)] : [unit.id],
    [unit, childOutlets],
  )
  const query = useEmployees({ companyId, actor, orgUnitIds: unitIds, enabled: true })

  // Resolve each linked login's USERNAME (userId → username) so the list shows the login id inline —
  // hunting through hundreds of detail drawers to recover a forgotten id is not viable. Gated on
  // canManageLogins (the /api/v1/users read is OPS-only); an hr-alone login falls back to a badge.
  const { company } = useSession()
  const team = useTeam({ companyId, actor, enabled: canManageLogins })
  // The team list returns the LOCAL username (org-service strips the `<companyCode>.` prefix for
  // display), but staff sign in with the FULL scoped id — re-add the prefix so the shown value is
  // the actual login id. An owner's email login has no prefix (left as-is).
  const usernameByUserId = useMemo(() => {
    const code = company?.companyCode
    return new Map(
      (team.data ?? []).map((m) => {
        const full = !code || m.username.includes('@') ? m.username : `${code}.${m.username}`
        return [m.id, full] as const
      }),
    )
  }, [team.data, company?.companyCode])
  // Phase 1 (HR-job-title vs. app-access-role visibility) — reuses the SAME team list above (no
  // extra call) to flag rows whose job title implies an access role the linked login doesn't hold
  // yet; only meaningful when `team` actually loaded (canManageLogins), same gate as usernames.
  const teamByUserId = useMemo(
    () => new Map((team.data ?? []).map((m) => [m.id, m] as const)),
    [team.data],
  )
  const [search, setSearch] = useState('')

  const unitName = (id: string | null) => units.find((u) => u.id === id)?.name ?? '—'

  const grouped = useMemo<GroupedEmployee[]>(() => {
    const byId = new Map<string, GroupedEmployee>()
    for (const row of query.data ?? []) {
      const existing = byId.get(row.employeeId)
      if (existing) {
        existing.rows.push(row)
        existing.hasCompensation = existing.hasCompensation || row.hasCompensation
      } else {
        byId.set(row.employeeId, {
          employeeId: row.employeeId,
          fullName: row.fullName,
          status: row.status,
          hasCompensation: row.hasCompensation,
          userId: row.userId,
          rows: [row],
        })
      }
    }
    return [...byId.values()]
  }, [query.data])

  const q = search.trim().toLowerCase()
  const filtered = useMemo(
    () =>
      q
        ? grouped.filter((e) => {
            const u = e.userId ? (usernameByUserId.get(e.userId) ?? '') : ''
            return e.fullName.toLowerCase().includes(q) || u.toLowerCase().includes(q)
          })
        : grouped,
    [grouped, q, usernameByUserId],
  )

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm text-ink-3">{t('hr.list.subtitle', { unit: unit.name })}</p>
        {/* Create only at the business-unit level; an outlet assigns an EXISTING employee. */}
        {unit.type === 'BUSINESS_UNIT' ? (
          <Button type="button" onClick={() => setDialog({ kind: 'create' })}>
            <Plus className="size-4" />
            {t('hr.list.add')}
          </Button>
        ) : (
          <Button type="button" onClick={() => setDialog({ kind: 'assignExisting' })}>
            <Plus className="size-4" />
            {t('hr.list.assignExisting')}
          </Button>
        )}
      </div>

      {!query.isLoading && !query.isError && grouped.length > 0 ? (
        <div className="relative">
          <Search
            className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-ink-3"
            aria-hidden="true"
          />
          <TextInput
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('hr.list.search')}
            aria-label={t('hr.list.search')}
            className="pl-10"
          />
        </div>
      ) : null}

      {query.isError ? (
        <Card className="space-y-3 p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto size-5" />
          {t('hr.list.error')}
          <ErrorDetails error={query.error} className="mx-auto max-w-md" />
        </Card>
      ) : query.isLoading ? (
        <ListSkeleton rows={5} avatar />
      ) : grouped.length === 0 ? (
        <EmptyState title={t('hr.list.empty')} hint={t('hr.list.emptyHint')} />
      ) : filtered.length === 0 ? (
        <p className="px-2 py-8 text-center text-sm text-ink-3">{t('hr.list.noMatch')}</p>
      ) : (
        <Card className="rounded-[20px] p-2.5">
          {filtered.map((employee) => (
            <EmployeeRow
              key={employee.employeeId}
              employee={employee}
              username={employee.userId ? (usernameByUserId.get(employee.userId) ?? null) : null}
              accessMismatch={employeeAccessMismatch(
                employee,
                employee.userId ? teamByUserId.get(employee.userId) : undefined,
              )}
              unitName={unitName}
              canManageLogins={canManageLogins}
              onManage={() => setDialog({ kind: 'detail', employeeId: employee.employeeId })}
              onCreateLogin={() =>
                setDialog({ kind: 'createLogin', employee: employee.rows[0] })
              }
            />
          ))}
        </Card>
      )}

      {dialog?.kind === 'create' || dialog?.kind === 'edit' ? (
        <EmployeeFormDialog
          unit={unit}
          childOutlets={childOutlets}
          companyId={companyId}
          actor={actor}
          edit={dialog.kind === 'edit' ? dialog.employee : null}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'assign' ? (
        <AssignDialog
          employee={dialog.employee}
          unit={unit}
          childOutlets={childOutlets}
          companyId={companyId}
          actor={actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'endAssignment' ? (
        <EndAssignmentDialog
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'compensation' ? (
        <CompensationDialog
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          baseCurrency={baseCurrency}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'terminate' ? (
        <TerminateDialog
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'createLogin' ? (
        <CreateLoginDialog
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'operatorPin' ? (
        <OperatorPinDialog
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          onClose={closeDialog}
        />
      ) : null}
      {dialog?.kind === 'detail'
        ? (() => {
            // Re-derived each render so the panel always shows the freshest grouped rows (an ended
            // assignment or termination refetches the list underneath it).
            const selected = grouped.find((g) => g.employeeId === dialog.employeeId)
            if (!selected) return null
            const primary = selected.rows[0]
            return (
              <EmployeeDetailDrawer
                employee={selected}
                unitName={unitName}
                companyId={companyId}
                actor={actor}
                canManageLogins={canManageLogins}
                onClose={() => setDialog(null)}
                onCreateLogin={() =>
                  setDialog({ kind: 'createLogin', employee: primary, fromDetail: true })
                }
                onEdit={() => setDialog({ kind: 'edit', employee: primary, fromDetail: true })}
                onAssign={() => setDialog({ kind: 'assign', employee: primary, fromDetail: true })}
                onEndAssignment={(row) =>
                  setDialog({ kind: 'endAssignment', employee: row, fromDetail: true })
                }
                onCompensation={() =>
                  setDialog({ kind: 'compensation', employee: primary, fromDetail: true })
                }
                onTerminate={() =>
                  setDialog({ kind: 'terminate', employee: primary, fromDetail: true })
                }
                onSetOperatorPin={() =>
                  setDialog({ kind: 'operatorPin', employee: primary, fromDetail: true })
                }
              />
            )
          })()
        : null}
      {dialog?.kind === 'assignExisting' ? (
        <AssignExistingDialog
          outlet={unit}
          units={units}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </div>
  )
}

/**
 * Phase 1 (HR-job-title vs. app-access-role visibility) — true when ANY of the employee's current
 * assignment job titles implies an access role the linked login's Team member doesn't actually
 * hold. `member` is `undefined` whenever the login isn't resolvable (no login, team list not
 * loaded, or `!canManageLogins` — the OPS-only `/api/v1/users` read never even fires then) — in
 * every one of those cases this returns `false` rather than guessing.
 */
function employeeAccessMismatch(employee: GroupedEmployee, member: TeamMember | undefined): boolean {
  if (!member) return false
  return employee.rows.some(
    (r) => r.assignmentId != null && hasAccessRoleMismatch(r.role, member.roles),
  )
}

function EmployeeRow({
  employee,
  username,
  accessMismatch,
  unitName,
  canManageLogins,
  onManage,
  onCreateLogin,
}: {
  employee: GroupedEmployee
  /** The linked login's username (the login id), resolved by the parent; null if none / not resolved. */
  username: string | null
  /** True when a job title implies an access role the linked login doesn't hold yet — see
   *  `employeeAccessMismatch`. */
  accessMismatch: boolean
  unitName: (id: string | null) => string
  canManageLogins: boolean
  onManage: () => void
  onCreateLogin: () => void
}) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const active = employee.status === 'ACTIVE'
  const initials = employee.fullName
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')

  return (
    <div className="flex items-center gap-3 rounded-xl px-2.5 py-2.5 transition-colors hover:bg-hover">
      <span className="grid size-9 shrink-0 place-items-center rounded-full bg-emerald-tint text-[13px] font-semibold text-emerald-2">
        {initials}
      </span>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={onManage}
            className={cn(
              'rounded text-left text-[14.5px] font-semibold hover:underline focus-visible:outline-2 focus-visible:outline-brand-500',
              active ? 'text-ink' : 'text-ink-3',
            )}
          >
            {employee.fullName}
          </button>
          {!active ? <Badge tone="amber">{t('hr.list.inactive')}</Badge> : null}
          {employee.hasCompensation ? (
            <Badge tone="emerald">{t('hr.list.compSet')}</Badge>
          ) : (
            <Badge tone="neutral">{t('hr.list.compNone')}</Badge>
          )}
          {/* The login id (username) inline + copy — so a forgotten id is one glance away, not a
              drawer dig. Falls back to a badge when the username couldn't be resolved (hr-alone). */}
          {username ? (
            <button
              type="button"
              onClick={() => {
                void navigator.clipboard?.writeText(username)
                setCopied(true)
                setTimeout(() => setCopied(false), 1500)
              }}
              aria-label={t('hr.list.copyUsername', { username })}
              title={t('hr.list.copyUsername', { username })}
              className="inline-flex items-center gap-1 rounded-full bg-emerald-tint py-0.5 pl-2 pr-1.5 font-mono text-[11px] font-semibold text-emerald-2 transition-colors hover:bg-brand-100/60 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald"
            >
              {username}
              {copied ? (
                <Check className="size-3" aria-hidden="true" />
              ) : (
                <Copy className="size-3 opacity-70" aria-hidden="true" />
              )}
            </button>
          ) : employee.userId ? (
            <Badge tone="info">{t('hr.list.hasLogin')}</Badge>
          ) : null}
          {/* Phase 1 (HR-job-title vs. app-access-role visibility) — tiny indicator only, no label,
              so it never crowds the row; the drawer's App access section has the full detail. */}
          {accessMismatch ? (
            <span
              role="img"
              aria-label={t('hr.list.accessMismatchHint')}
              title={t('hr.list.accessMismatchHint')}
              className="inline-flex size-5 shrink-0 items-center justify-center rounded-full bg-tint-warning text-amber-2"
            >
              <TriangleAlert className="size-3" aria-hidden="true" />
            </span>
          ) : null}
        </div>
        <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
          {/* ACTIVE only (see `isActiveAssignment`) — an already-ended assignment can still satisfy
              the list endpoint's "current as of today" window until its chosen end date passes, so
              filtering only on assignmentId is not enough (it would show alongside real active
              ones). */}
          {employee.rows.filter(isActiveAssignment).length === 0 ? (
            <span className="text-xs text-ink-3">{t('hr.list.unassigned')}</span>
          ) : (
            employee.rows
              .filter(isActiveAssignment)
              .map((r) => (
                <span
                  key={r.assignmentId}
                  className="rounded-full bg-paper px-2 py-0.5 text-[11px] text-ink-2"
                >
                  {r.role} · {unitName(r.orgUnitId)}
                </span>
              ))
          )}
        </div>
      </div>

      {/* Always-visible actions (Native Console Web design): the six hover-hidden actions became
          one Kelola button opening the side panel — nothing is discoverable-by-accident anymore. */}
      <div className="flex shrink-0 items-center gap-2">
        {canManageLogins && !employee.userId && active ? (
          <button
            type="button"
            onClick={onCreateLogin}
            className="h-[34px] rounded-[10px] border border-emerald-line bg-emerald-tint px-3 text-[12.5px] font-semibold text-emerald-2 transition-colors hover:bg-brand-100/60 focus-visible:outline-2 focus-visible:outline-brand-500"
          >
            {t('hr.list.actionCreateLogin')}
          </button>
        ) : null}
        <button
          type="button"
          onClick={onManage}
          className="h-[34px] rounded-[10px] border border-line bg-surface px-3.5 text-[12.5px] font-semibold text-ink-2 transition-colors hover:border-brand-300 hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-brand-500"
        >
          {t('hr.list.manage')}
        </button>
      </div>
    </div>
  )
}
