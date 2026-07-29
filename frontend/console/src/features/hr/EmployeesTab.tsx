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
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { EmptyState } from '@/features/_shared/financeUi'
import type { OrgUnit } from '@/features/org/api'
import { cn } from '@/lib/cn'
import { useEmployees, type EmployeeListRow } from './api'
import { CreateLoginDialog } from './CreateLoginDialog'
import { EmployeeDetailDrawer } from './EmployeeDetailDrawer'
import {
  AssignDialog,
  AssignExistingDialog,
  CompensationDialog,
  EmployeeFormDialog,
  EndAssignmentDialog,
  TerminateDialog,
} from './parts'

type DialogState =
  | { kind: 'create' }
  | { kind: 'edit'; employee: EmployeeListRow }
  | { kind: 'assign'; employee: EmployeeListRow }
  | { kind: 'endAssignment'; employee: EmployeeListRow }
  | { kind: 'compensation'; employee: EmployeeListRow }
  | { kind: 'terminate'; employee: EmployeeListRow }
  | { kind: 'createLogin'; employee: EmployeeListRow }
  | { kind: 'detail'; employee: EmployeeListRow }
  | { kind: 'assignExisting' }

/** One employee, grouped from their (possibly several) current-assignment rows. */
interface GroupedEmployee {
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
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('hr.list.error')}
        </Card>
      ) : query.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : grouped.length === 0 ? (
        <EmptyState title={t('hr.list.empty')} hint={t('hr.list.emptyHint')} />
      ) : (
        <Card className="rounded-[20px] p-2.5">
          {grouped.map((employee) => (
            <EmployeeRow
              key={employee.employeeId}
              employee={employee}
              unitName={unitName}
              onView={() => setDialog({ kind: 'detail', employee: employee.rows[0] })}
              onAssign={() => setDialog({ kind: 'assign', employee: employee.rows[0] })}
              onCompensation={() =>
                setDialog({ kind: 'compensation', employee: employee.rows[0] })
              }
              onEdit={() => setDialog({ kind: 'edit', employee: employee.rows[0] })}
              onEndAssignment={() =>
                setDialog({ kind: 'endAssignment', employee: employee.rows[0] })
              }
              onTerminate={() => setDialog({ kind: 'terminate', employee: employee.rows[0] })}
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
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'assign' ? (
        <AssignDialog
          employee={dialog.employee}
          unit={unit}
          childOutlets={childOutlets}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'endAssignment' ? (
        <EndAssignmentDialog
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'compensation' ? (
        <CompensationDialog
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          baseCurrency={baseCurrency}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'terminate' ? (
        <TerminateDialog
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'createLogin' ? (
        <CreateLoginDialog
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'detail' ? (
        <EmployeeDetailDrawer
          employee={dialog.employee}
          companyId={companyId}
          actor={actor}
          onClose={() => setDialog(null)}
          onCreateLogin={() => setDialog({ kind: 'createLogin', employee: dialog.employee })}
        />
      ) : null}
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
  onView,
  onAssign,
  onCompensation,
  onEdit,
  onEndAssignment,
  onTerminate,
  onCreateLogin,
}: {
  employee: GroupedEmployee
  unitName: (id: string | null) => string
  onView: () => void
  onAssign: () => void
  onCompensation: () => void
  onEdit: () => void
  onEndAssignment: () => void
  onTerminate: () => void
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
    <div className="group flex items-center gap-3 rounded-xl px-2.5 py-2.5 transition-colors hover:bg-hover">
      <span className="grid size-9 shrink-0 place-items-center rounded-full bg-emerald-tint text-[13px] font-semibold text-emerald-2">
        {initials}
      </span>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={onView}
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

      {/* Hover-revealed row actions (Team.tsx pattern). */}
      <div className="flex items-center gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
        {!employee.userId && active ? (
          <RowAction label={t('hr.list.actionCreateLogin')} onClick={onCreateLogin} />
        ) : null}
        <RowAction label={t('hr.list.actionAssign')} onClick={onAssign} />
        <RowAction label={t('hr.list.actionCompensation')} onClick={onCompensation} />
        <RowAction label={t('hr.list.actionEdit')} onClick={onEdit} />
        {employee.rows.some((r) => r.assignmentId) ? (
          <RowAction label={t('hr.list.actionEndAssignment')} onClick={onEndAssignment} />
        ) : null}
        {active ? (
          <button
            type="button"
            className="rounded-md px-2 py-1 text-xs text-loss/80 hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-loss"
            onClick={onTerminate}
          >
            {t('hr.list.actionTerminate')}
          </button>
        ) : null}
      </div>
    </div>
  )
}

function RowAction({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      className="rounded-md px-2 py-1 text-xs text-ink-3 hover:bg-paper hover:text-ink focus-visible:outline-2 focus-visible:outline-brand-500"
      onClick={onClick}
    >
      {label}
    </button>
  )
}
