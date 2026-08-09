/**
 * Employees tab of the org-unit hub — the Odoo-style HR list scoped to the current unit. The BU
 * rollup is CLIENT-computed: a business unit's scope is [itself, ...its child outlets] from the
 * org tree the hub already has (employee-service's projection holds no parent_id). Employees are
 * HR RECORDS, deliberately separate from the Team page's login users. No PII renders here — the
 * list endpoint never returns NIK / bank account / amounts.
 */

import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { ErrorDetails } from '@/components/ErrorDetails'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import type { OrgUnit } from '@/features/org/api'
import { cn } from '@/lib/cn'
import { useEmployees, type EmployeeListRow } from './api'
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
}: {
  unit: OrgUnit
  childOutlets: OrgUnit[]
  units: OrgUnit[]
  companyId: string
  actor: string
  baseCurrency: string
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
      ) : (
        <Card className="rounded-[20px] p-2.5">
          {grouped.map((employee) => (
            <EmployeeRow
              key={employee.employeeId}
              employee={employee}
              unitName={unitName}
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

function EmployeeRow({
  employee,
  unitName,
  onManage,
  onCreateLogin,
}: {
  employee: GroupedEmployee
  unitName: (id: string | null) => string
  onManage: () => void
  onCreateLogin: () => void
}) {
  const { t } = useTranslation()
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
          {employee.userId ? <Badge tone="info">{t('hr.list.hasLogin')}</Badge> : null}
        </div>
        <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
          {employee.rows.filter((r) => r.assignmentId).length === 0 ? (
            <span className="text-xs text-ink-3">{t('hr.list.unassigned')}</span>
          ) : (
            employee.rows
              .filter((r) => r.assignmentId)
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
        {!employee.userId && active ? (
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
