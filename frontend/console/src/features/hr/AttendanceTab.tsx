/**
 * Attendance tab of the org-unit hub (ADR 0033, Track P Phase P6): the manager's leave-request and
 * overtime-entry approval queues (SUBMITTED-first, server-ordered), the per-employee derived
 * annual-leave balances with a grant/correction dialog, and the tenant's work-calendar card. This
 * tab is COMPANY-WIDE like Payroll (leave/overtime requests carry no org-unit scope in v1 — ADR
 * 0033 §6), so it renders identically regardless of which unit's hub it sits on.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CalendarDays, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { Segmented } from '@/components/ui/Segmented'
import { EmptyState } from '@/features/_shared/financeUi'
import { DialogOverlay } from '@/features/org/parts'
import { cn } from '@/lib/cn'
import {
  useAdjustLeaveBalance,
  useApproveLeaveRequest,
  useApproveOvertimeEntry,
  useLeaveBalances,
  useLeaveRequests,
  useOvertimeEntries,
  useRejectLeaveRequest,
  useRejectOvertimeEntry,
  useUpsertWorkCalendar,
  useWorkCalendar,
  type LeaveBalanceRow,
  type LeaveRequestSummary,
  type OvertimeEntrySummary,
  type TimeoffStatus,
} from './api'

type AttendanceView = 'leave' | 'overtime' | 'balances' | 'calendar'

type DecideTarget =
  | { kind: 'leave'; id: string; label: string; action: 'approve' | 'reject' }
  | { kind: 'overtime'; id: string; label: string; action: 'approve' | 'reject' }

const STATUS_FILTERS: { value: string; label: string }[] = [
  { value: '', label: 'all' },
  { value: 'SUBMITTED', label: 'submitted' },
  { value: 'APPROVED', label: 'approved' },
  { value: 'REJECTED', label: 'rejected' },
  { value: 'CANCELLED', label: 'cancelled' },
]

export function AttendanceTab({ companyId, actor }: { companyId: string; actor: string }) {
  const { t } = useTranslation()
  const [view, setView] = useState<AttendanceView>('leave')

  return (
    <div className="flex flex-col gap-4">
      <Segmented<AttendanceView>
        options={[
          { value: 'leave', label: t('attendance.view.leave') },
          { value: 'overtime', label: t('attendance.view.overtime') },
          { value: 'balances', label: t('attendance.view.balances') },
          { value: 'calendar', label: t('attendance.view.calendar') },
        ]}
        value={view}
        onChange={setView}
        ariaLabel={t('attendance.view.label')}
      />

      {view === 'leave' ? <LeaveRequestsSection companyId={companyId} actor={actor} /> : null}
      {view === 'overtime' ? <OvertimeEntriesSection companyId={companyId} actor={actor} /> : null}
      {view === 'balances' ? <BalancesSection companyId={companyId} actor={actor} /> : null}
      {view === 'calendar' ? <CalendarSection companyId={companyId} actor={actor} /> : null}
    </div>
  )
}

function StatusToneBadge({ status }: { status: TimeoffStatus }) {
  const { t } = useTranslation()
  const tone =
    status === 'APPROVED'
      ? 'profit'
      : status === 'REJECTED'
        ? 'loss'
        : status === 'CANCELLED'
          ? 'neutral'
          : 'amber'
  return <Badge tone={tone}>{t(`attendance.status.${status}`)}</Badge>
}

function StatusFilterBar({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-wrap gap-1.5">
      {STATUS_FILTERS.map((f) => (
        <button
          key={f.value}
          type="button"
          onClick={() => onChange(f.value)}
          className={cn(
            'rounded-full px-3 py-1.5 text-xs font-semibold transition-colors',
            value === f.value
              ? 'bg-brand-600 text-white'
              : 'bg-ink-50 text-ink-2 hover:bg-ink-100',
          )}
        >
          {t(`attendance.statusFilter.${f.label}`)}
        </button>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Leave requests
// ---------------------------------------------------------------------------

function LeaveRequestsSection({ companyId, actor }: { companyId: string; actor: string }) {
  const { t } = useTranslation()
  const [status, setStatus] = useState('')
  const [decide, setDecide] = useState<DecideTarget | null>(null)
  const query = useLeaveRequests({ companyId, actor, status: status || undefined, enabled: true })

  const rows = query.data?.content ?? []

  return (
    <div className="flex flex-col gap-3">
      <StatusFilterBar value={status} onChange={setStatus} />
      {query.isLoading ? (
        <ListSkeleton rows={5} />
      ) : rows.length === 0 ? (
        <EmptyState title={t('attendance.leave.empty')} hint={t('attendance.leave.emptyHint')} />
      ) : (
        <Card className="rounded-[20px] p-2.5">
          {rows.map((row) => (
            <LeaveRequestRow
              key={row.id}
              row={row}
              onApprove={() =>
                setDecide({ kind: 'leave', id: row.id, label: row.employeeName, action: 'approve' })
              }
              onReject={() =>
                setDecide({ kind: 'leave', id: row.id, label: row.employeeName, action: 'reject' })
              }
            />
          ))}
        </Card>
      )}

      {decide?.kind === 'leave' ? (
        <DecideDialog
          companyId={companyId}
          actor={actor}
          target={decide}
          onClose={() => setDecide(null)}
        />
      ) : null}
    </div>
  )
}

function LeaveRequestRow({
  row,
  onApprove,
  onReject,
}: {
  row: LeaveRequestSummary
  onApprove: () => void
  onReject: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl px-2.5 py-2.5 hover:bg-hover">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[14.5px] font-semibold text-ink">{row.employeeName}</span>
          <Badge tone="neutral">{t(`attendance.leaveType.${row.leaveType}`)}</Badge>
          <StatusToneBadge status={row.status} />
        </div>
        <p className="mt-0.5 text-xs text-ink-3">
          {row.startDate} — {row.endDate} · {t('attendance.leave.days', { count: row.days })}
          {row.decisionNote ? <span> · “{row.decisionNote}”</span> : null}
        </p>
      </div>
      {row.status === 'SUBMITTED' ? (
        <div className="flex items-center gap-2">
          <Button type="button" variant="outline" onClick={onReject}>
            {t('attendance.actions.reject')}
          </Button>
          <Button type="button" onClick={onApprove}>
            {t('attendance.actions.approve')}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Overtime entries
// ---------------------------------------------------------------------------

function OvertimeEntriesSection({ companyId, actor }: { companyId: string; actor: string }) {
  const { t } = useTranslation()
  const [status, setStatus] = useState('')
  const [decide, setDecide] = useState<DecideTarget | null>(null)
  const query = useOvertimeEntries({ companyId, actor, status: status || undefined, enabled: true })

  const rows = query.data?.content ?? []

  return (
    <div className="flex flex-col gap-3">
      <StatusFilterBar value={status} onChange={setStatus} />
      {query.isLoading ? (
        <ListSkeleton rows={5} />
      ) : rows.length === 0 ? (
        <EmptyState title={t('attendance.overtime.empty')} hint={t('attendance.overtime.emptyHint')} />
      ) : (
        <Card className="rounded-[20px] p-2.5">
          {rows.map((row) => (
            <OvertimeEntryRow
              key={row.id}
              row={row}
              onApprove={() =>
                setDecide({ kind: 'overtime', id: row.id, label: row.employeeName, action: 'approve' })
              }
              onReject={() =>
                setDecide({ kind: 'overtime', id: row.id, label: row.employeeName, action: 'reject' })
              }
            />
          ))}
        </Card>
      )}

      {decide?.kind === 'overtime' ? (
        <DecideDialog
          companyId={companyId}
          actor={actor}
          target={decide}
          onClose={() => setDecide(null)}
        />
      ) : null}
    </div>
  )
}

function OvertimeEntryRow({
  row,
  onApprove,
  onReject,
}: {
  row: OvertimeEntrySummary
  onApprove: () => void
  onReject: () => void
}) {
  const { t } = useTranslation()
  const hours = Math.floor(row.minutes / 60)
  const minutes = row.minutes % 60
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl px-2.5 py-2.5 hover:bg-hover">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[14.5px] font-semibold text-ink">{row.employeeName}</span>
          <Badge tone="neutral">{t(`attendance.dayKind.${row.dayKind}`)}</Badge>
          <StatusToneBadge status={row.status} />
        </div>
        <p className="mt-0.5 text-xs text-ink-3">
          {row.workDate} · {t('attendance.overtime.duration', { hours, minutes })}
          {row.decisionNote ? <span> · “{row.decisionNote}”</span> : null}
        </p>
      </div>
      {row.status === 'SUBMITTED' ? (
        <div className="flex items-center gap-2">
          <Button type="button" variant="outline" onClick={onReject}>
            {t('attendance.actions.reject')}
          </Button>
          <Button type="button" onClick={onApprove}>
            {t('attendance.actions.approve')}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

/** Shared approve/reject dialog for both leave requests and overtime entries. */
function DecideDialog({
  companyId,
  actor,
  target,
  onClose,
}: {
  companyId: string
  actor: string
  target: DecideTarget
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [note, setNote] = useState('')

  const approveLeave = useApproveLeaveRequest({ companyId, actor })
  const rejectLeave = useRejectLeaveRequest({ companyId, actor })
  const approveOvertime = useApproveOvertimeEntry({ companyId, actor })
  const rejectOvertime = useRejectOvertimeEntry({ companyId, actor })

  const isReject = target.action === 'reject'
  const pending =
    approveLeave.isPending || rejectLeave.isPending || approveOvertime.isPending || rejectOvertime.isPending
  const isError =
    approveLeave.isError || rejectLeave.isError || approveOvertime.isError || rejectOvertime.isError

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const idempotencyKey = crypto.randomUUID()
    if (target.kind === 'leave') {
      if (isReject) {
        rejectLeave.mutate({ requestId: target.id, note, idempotencyKey }, { onSuccess: onClose })
      } else {
        approveLeave.mutate(
          { requestId: target.id, note: note || undefined, idempotencyKey },
          { onSuccess: onClose },
        )
      }
    } else if (isReject) {
      rejectOvertime.mutate({ entryId: target.id, note, idempotencyKey }, { onSuccess: onClose })
    } else {
      approveOvertime.mutate(
        { entryId: target.id, note: note || undefined, idempotencyKey },
        { onSuccess: onClose },
      )
    }
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t(isReject ? 'attendance.decide.rejectTitle' : 'attendance.decide.approveTitle', {
            name: target.label,
          })}
        </h2>
        <Field
          label={isReject ? t('attendance.decide.noteRequired') : t('attendance.decide.noteOptional')}
          htmlFor="decide-note"
        >
          <textarea
            id="decide-note"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            required={isReject}
            rows={3}
            maxLength={4000}
            className="w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
          />
        </Field>
        {isError ? <p className="text-sm text-loss">{t('attendance.decide.error')}</p> : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={pending || (isReject && !note.trim())}>
            {pending
              ? t('attendance.decide.saving')
              : t(isReject ? 'attendance.actions.reject' : 'attendance.actions.approve')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Leave balances
// ---------------------------------------------------------------------------

function BalancesSection({ companyId, actor }: { companyId: string; actor: string }) {
  const { t } = useTranslation()
  const [year, setYear] = useState(new Date().getFullYear())
  const [adjustTarget, setAdjustTarget] = useState<LeaveBalanceRow | null>(null)
  const query = useLeaveBalances({ companyId, actor, year, enabled: true })

  const rows = query.data?.content ?? []

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <Button type="button" variant="outline" onClick={() => setYear((y) => y - 1)}>
          ‹
        </Button>
        <span className="tnum min-w-[4ch] text-center text-sm font-semibold text-ink">{year}</span>
        <Button type="button" variant="outline" onClick={() => setYear((y) => y + 1)}>
          ›
        </Button>
      </div>

      {query.isLoading ? (
        <ListSkeleton rows={5} />
      ) : rows.length === 0 ? (
        <EmptyState title={t('attendance.balances.empty')} hint={t('attendance.balances.emptyHint')} />
      ) : (
        <Card className="overflow-x-auto rounded-[20px] p-2.5">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-[11px] uppercase tracking-wider text-ink-3">
                <th className="px-2.5 py-2 font-semibold">{t('attendance.balances.employee')}</th>
                <th className="px-2.5 py-2 text-right font-semibold">{t('attendance.balances.granted')}</th>
                <th className="px-2.5 py-2 text-right font-semibold">{t('attendance.balances.adjustment')}</th>
                <th className="px-2.5 py-2 text-right font-semibold">{t('attendance.balances.used')}</th>
                <th className="px-2.5 py-2 text-right font-semibold">{t('attendance.balances.remaining')}</th>
                <th className="px-2.5 py-2" />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.employeeId} className="text-ink-2">
                  <td className="px-2.5 py-2 font-medium text-ink">{row.employeeName}</td>
                  <td className="tnum px-2.5 py-2 text-right">{row.grantedDays}</td>
                  <td className="tnum px-2.5 py-2 text-right">
                    {row.adjustmentDays > 0 ? `+${row.adjustmentDays}` : row.adjustmentDays}
                  </td>
                  <td className="tnum px-2.5 py-2 text-right">{row.usedDays}</td>
                  <td
                    className={cn(
                      'tnum px-2.5 py-2 text-right font-semibold',
                      row.remaining < 0 ? 'text-loss' : 'text-ink',
                    )}
                  >
                    {row.remaining}
                  </td>
                  <td className="px-2.5 py-2 text-right">
                    <button
                      type="button"
                      onClick={() => setAdjustTarget(row)}
                      className="rounded-md px-2 py-1 text-xs text-ink-3 hover:bg-paper hover:text-ink"
                    >
                      {t('attendance.balances.adjust')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {adjustTarget ? (
        <AdjustBalanceDialog
          companyId={companyId}
          actor={actor}
          year={year}
          row={adjustTarget}
          onClose={() => setAdjustTarget(null)}
        />
      ) : null}
    </div>
  )
}

function AdjustBalanceDialog({
  companyId,
  actor,
  year,
  row,
  onClose,
}: {
  companyId: string
  actor: string
  year: number
  row: LeaveBalanceRow
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [adjustmentDays, setAdjustmentDays] = useState(String(row.adjustmentDays))
  const adjust = useAdjustLeaveBalance({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const parsed = Number.parseInt(adjustmentDays, 10)
    if (Number.isNaN(parsed)) return
    adjust.mutate(
      { employeeId: row.employeeId, year, adjustmentDays: parsed },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t('attendance.balances.adjustTitle', { name: row.employeeName, year })}
        </h2>
        <p className="text-xs text-ink-3">{t('attendance.balances.adjustHint', { granted: row.grantedDays })}</p>
        <Field label={t('attendance.balances.adjustment')} htmlFor="adjust-days">
          <TextInput
            id="adjust-days"
            type="number"
            value={adjustmentDays}
            onChange={(e) => setAdjustmentDays(e.target.value)}
            autoFocus
          />
        </Field>
        {adjust.isError ? <p className="text-sm text-loss">{t('attendance.decide.error')}</p> : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={adjust.isPending}>
            {adjust.isPending ? t('hr.form.saving') : t('common.save')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

// ---------------------------------------------------------------------------
// Work calendar
// ---------------------------------------------------------------------------

function CalendarSection({ companyId, actor }: { companyId: string; actor: string }) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState(false)
  const query = useWorkCalendar({ companyId, actor, enabled: true })

  if (query.isLoading) {
    return (
      <Card className="p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <Skeleton className="h-5 w-40" />
            <Skeleton className="mt-2 h-4 w-64 max-w-full" />
          </div>
          <Skeleton className="h-9 w-24 rounded-xl" />
        </div>
        <div className="mt-5 grid gap-4 sm:grid-cols-2">
          <Skeleton className="h-20 rounded-xl" />
          <Skeleton className="h-20 rounded-xl" />
        </div>
      </Card>
    )
  }
  if (query.isError || !query.data) {
    return (
      <Card className="space-y-2 p-8 text-center text-sm text-loss">
        <TriangleAlert className="mx-auto size-5" />
        {t('attendance.calendar.error')}
      </Card>
    )
  }

  return (
    <>
      <Card className="p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="flex items-center gap-2 text-ink">
              <CalendarDays className="size-5" aria-hidden="true" />
              <h3 className="text-[15px] font-semibold">{t('attendance.calendar.title')}</h3>
            </div>
            <p className="mt-1 max-w-md text-sm text-ink-3">{t('attendance.calendar.body')}</p>
          </div>
          <Button type="button" variant="outline" onClick={() => setEditing(true)}>
            {t('attendance.calendar.edit')}
          </Button>
        </div>
        <div className="mt-5 grid gap-4 sm:grid-cols-2">
          <div className="rounded-xl bg-paper p-4">
            <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
              {t('attendance.calendar.daysPerWeek')}
            </p>
            <p className="tnum mt-1 text-2xl font-bold text-ink">{query.data.daysPerWeek}</p>
          </div>
          <div className="rounded-xl bg-paper p-4">
            <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
              {t('attendance.calendar.monthlyDivisor')}
            </p>
            <p className="tnum mt-1 text-2xl font-bold text-ink">{query.data.monthlyDivisor}</p>
          </div>
        </div>
      </Card>

      {editing ? (
        <EditCalendarDialog
          companyId={companyId}
          actor={actor}
          current={query.data}
          onClose={() => setEditing(false)}
        />
      ) : null}
    </>
  )
}

function EditCalendarDialog({
  companyId,
  actor,
  current,
  onClose,
}: {
  companyId: string
  actor: string
  current: { daysPerWeek: number; monthlyDivisor: number }
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [daysPerWeek, setDaysPerWeek] = useState<5 | 6>(current.daysPerWeek === 6 ? 6 : 5)
  const [monthlyDivisor, setMonthlyDivisor] = useState<21 | 25>(current.monthlyDivisor === 25 ? 25 : 21)
  const upsert = useUpsertWorkCalendar({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    upsert.mutate({ daysPerWeek, monthlyDivisor }, { onSuccess: onClose })
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('attendance.calendar.editTitle')}</h2>
        <Field label={t('attendance.calendar.daysPerWeek')} htmlFor="cal-days">
          <div className="flex gap-2">
            {[5, 6].map((d) => (
              <button
                key={d}
                type="button"
                onClick={() => setDaysPerWeek(d as 5 | 6)}
                className={cn(
                  'flex-1 rounded-xl border px-3 py-2.5 text-sm font-semibold',
                  daysPerWeek === d
                    ? 'border-emerald bg-emerald-tint text-emerald-2'
                    : 'border-line text-ink-2 hover:bg-hover',
                )}
              >
                {d}
              </button>
            ))}
          </div>
        </Field>
        <Field label={t('attendance.calendar.monthlyDivisor')} htmlFor="cal-divisor">
          <div className="flex gap-2">
            {[21, 25].map((d) => (
              <button
                key={d}
                type="button"
                onClick={() => setMonthlyDivisor(d as 21 | 25)}
                className={cn(
                  'flex-1 rounded-xl border px-3 py-2.5 text-sm font-semibold',
                  monthlyDivisor === d
                    ? 'border-emerald bg-emerald-tint text-emerald-2'
                    : 'border-line text-ink-2 hover:bg-hover',
                )}
              >
                {d}
              </button>
            ))}
          </div>
        </Field>
        {upsert.isError ? <p className="text-sm text-loss">{t('attendance.decide.error')}</p> : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={upsert.isPending}>
            {upsert.isPending ? t('hr.form.saving') : t('common.save')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
