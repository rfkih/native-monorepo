/**
 * "My time off" card on the employee self-service dashboard (/me) — ADR 0033, Track P Phase P6:
 * the caller's own derived annual-leave balance, request-leave / log-overtime dialogs, and a
 * combined list of the caller's own requests with cancel for anything still SUBMITTED.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { Spinner } from '@/components/ui/Spinner'
import { DialogOverlay } from '@/features/org/parts'
import { cn } from '@/lib/cn'
import {
  useCancelLeaveRequest,
  useCancelOvertimeEntry,
  useCreateLeaveRequest,
  useCreateOvertimeEntry,
  useMyLeaveBalance,
  useMyLeaveRequests,
  useMyOvertimeEntries,
  type MyLeaveRequest,
  type MyOvertimeEntry,
  type TimeoffStatus,
} from './api'

type DialogState = { kind: 'leave' } | { kind: 'overtime' }

function StatusBadge({ status }: { status: TimeoffStatus }) {
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

export function MyTimeoff({
  companyId,
  actor,
  hideBalance = false,
}: {
  companyId: string
  actor: string
  /** Desktop /me shows the balance as a rail card ({@link LeaveBalanceCard}) — skip it here. */
  hideBalance?: boolean
}) {
  const { t } = useTranslation()
  const [dialog, setDialog] = useState<DialogState | null>(null)
  const year = new Date().getFullYear()

  const balance = useMyLeaveBalance({ companyId, actor, year, enabled: !hideBalance })
  const leaveRequests = useMyLeaveRequests({ companyId, actor, enabled: true })
  const overtimeEntries = useMyOvertimeEntries({ companyId, actor, enabled: true })
  const cancelLeave = useCancelLeaveRequest({ companyId, actor })
  const cancelOvertime = useCancelOvertimeEntry({ companyId, actor })

  return (
    <section className="min-w-0">
      <div className="flex flex-wrap items-center justify-between gap-3 max-sm:flex-col max-sm:items-stretch">
        <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
          {t('me.timeoff.title')}
        </h2>
        <div className="flex gap-2">
          <Button
            type="button"
            variant="outline"
            className="whitespace-nowrap max-sm:h-[52px] max-sm:flex-1 max-sm:rounded-[15px]"
            onClick={() => setDialog({ kind: 'overtime' })}
          >
            {t('me.timeoff.logOvertime')}
          </Button>
          <Button
            type="button"
            className="whitespace-nowrap max-sm:h-[52px] max-sm:flex-1 max-sm:rounded-[15px]"
            onClick={() => setDialog({ kind: 'leave' })}
          >
            {t('me.timeoff.requestLeave')}
          </Button>
        </div>
      </div>

      {hideBalance ? null : (
        <Card className="mt-2 grid gap-4 p-5 sm:grid-cols-4">
          <BalanceTile label={t('me.timeoff.granted')} value={balance.data?.grantedDays} loading={balance.isLoading} />
          <BalanceTile
            label={t('me.timeoff.adjustment')}
            value={balance.data?.adjustmentDays}
            loading={balance.isLoading}
          />
          <BalanceTile label={t('me.timeoff.used')} value={balance.data?.usedDays} loading={balance.isLoading} />
          <BalanceTile
            label={t('me.timeoff.remaining')}
            value={balance.data?.remaining}
            loading={balance.isLoading}
            emphatic
          />
        </Card>
      )}

      {/* My leave requests */}
      <h3 className="mt-4 text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('me.timeoff.myLeaveRequests')}
      </h3>
      {leaveRequests.isLoading ? (
        <Card className="mt-2 p-6 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : (leaveRequests.data ?? []).length === 0 ? (
        <p className="mt-2 text-sm text-ink-3">{t('me.timeoff.leaveEmpty')}</p>
      ) : (
        <Card className="mt-2 rounded-[20px] p-2.5">
          {(leaveRequests.data ?? []).map((row) => (
            <LeaveRow
              key={row.id}
              row={row}
              onCancel={() =>
                cancelLeave.mutate({ requestId: row.id, idempotencyKey: crypto.randomUUID() })
              }
              cancelling={cancelLeave.isPending}
            />
          ))}
        </Card>
      )}

      {/* My overtime entries */}
      <h3 className="mt-4 text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('me.timeoff.myOvertimeEntries')}
      </h3>
      {overtimeEntries.isLoading ? (
        <Card className="mt-2 p-6 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : (overtimeEntries.data ?? []).length === 0 ? (
        <p className="mt-2 text-sm text-ink-3">{t('me.timeoff.overtimeEmpty')}</p>
      ) : (
        <Card className="mt-2 rounded-[20px] p-2.5">
          {(overtimeEntries.data ?? []).map((row) => (
            <OvertimeRow
              key={row.id}
              row={row}
              onCancel={() =>
                cancelOvertime.mutate({ entryId: row.id, idempotencyKey: crypto.randomUUID() })
              }
              cancelling={cancelOvertime.isPending}
            />
          ))}
        </Card>
      )}

      {dialog?.kind === 'leave' ? (
        <RequestLeaveDialog companyId={companyId} actor={actor} onClose={() => setDialog(null)} />
      ) : null}
      {dialog?.kind === 'overtime' ? (
        <LogOvertimeDialog companyId={companyId} actor={actor} onClose={() => setDialog(null)} />
      ) : null}
    </section>
  )
}

/**
 * The desktop rail's leave-balance card (Native Console Web design — /me two-column re-fit):
 * remaining days as the headline, a used-fraction bar, and the used-of-total footnote. Same
 * derived balance the {@link MyTimeoff} card shows on the phone; the query cache is shared.
 */
export function LeaveBalanceCard({ companyId, actor }: { companyId: string; actor: string }) {
  const { t } = useTranslation()
  const year = new Date().getFullYear()
  const balance = useMyLeaveBalance({ companyId, actor, year, enabled: true })
  const total = balance.data ? balance.data.grantedDays + balance.data.adjustmentDays : 0
  const used = balance.data?.usedDays ?? 0
  const usedFrac = total > 0 ? Math.min(1, used / total) : 0
  return (
    <Card className="p-5">
      <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('me.timeoff.balanceTitle', { year })}
      </h2>
      <div className="mt-2 flex items-baseline gap-2">
        <span className="tnum font-mono text-[32px] font-bold leading-none text-emerald-2">
          {balance.isLoading ? '…' : (balance.data?.remaining ?? 0)}
        </span>
        <span className="text-[13px] font-medium text-ink-3">{t('me.timeoff.daysLeft')}</span>
      </div>
      <div className="mt-3.5 h-2 overflow-hidden rounded-full bg-ink-50">
        <div
          className="h-full rounded-full bg-emerald"
          style={{ width: `${usedFrac * 100}%` }}
        />
      </div>
      <p className="mt-2 text-xs text-ink-3">
        {t('me.timeoff.usedOf', { used, total })}
      </p>
    </Card>
  )
}

function BalanceTile({
  label,
  value,
  loading,
  emphatic,
}: {
  label: string
  value: number | undefined
  loading: boolean
  emphatic?: boolean
}) {
  return (
    <div>
      <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">{label}</p>
      <p className={cn('tnum mt-1 text-2xl font-bold', emphatic ? 'text-emerald-2' : 'text-ink')}>
        {loading ? '…' : (value ?? 0)}
      </p>
    </div>
  )
}

function LeaveRow({
  row,
  onCancel,
  cancelling,
}: {
  row: MyLeaveRequest
  onCancel: () => void
  cancelling: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl px-3 py-2.5">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-semibold text-ink">{t(`attendance.leaveType.${row.leaveType}`)}</span>
          <StatusBadge status={row.status} />
        </div>
        <p className="mt-0.5 text-xs text-ink-3">
          {row.startDate} — {row.endDate} · {t('attendance.leave.days', { count: row.days })}
        </p>
      </div>
      {row.status === 'SUBMITTED' ? (
        <Button type="button" variant="outline" onClick={onCancel} disabled={cancelling}>
          {t('me.timeoff.cancel')}
        </Button>
      ) : null}
    </div>
  )
}

function OvertimeRow({
  row,
  onCancel,
  cancelling,
}: {
  row: MyOvertimeEntry
  onCancel: () => void
  cancelling: boolean
}) {
  const { t } = useTranslation()
  const hours = Math.floor(row.minutes / 60)
  const minutes = row.minutes % 60
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl px-3 py-2.5">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-semibold text-ink">{t(`attendance.dayKind.${row.dayKind}`)}</span>
          <StatusBadge status={row.status} />
        </div>
        <p className="mt-0.5 text-xs text-ink-3">
          {row.workDate} · {t('attendance.overtime.duration', { hours, minutes })}
        </p>
      </div>
      {row.status === 'SUBMITTED' ? (
        <Button type="button" variant="outline" onClick={onCancel} disabled={cancelling}>
          {t('me.timeoff.cancel')}
        </Button>
      ) : null}
    </div>
  )
}

function RequestLeaveDialog({
  companyId,
  actor,
  onClose,
}: {
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [leaveType, setLeaveType] = useState<'ANNUAL' | 'UNPAID' | 'SICK'>('ANNUAL')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [days, setDays] = useState('1')
  const create = useCreateLeaveRequest({ companyId, actor })
  // Track P Phase P7 review W2: a range spanning two calendar months is rejected 422 — surfaced
  // distinctly from the generic error so the employee understands WHY (split into two requests).
  const crossMonth = (create.error as { status?: number } | null)?.status === 422

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const parsedDays = Number.parseInt(days, 10)
    if (!startDate || !endDate || Number.isNaN(parsedDays) || parsedDays <= 0) return
    create.mutate(
      { body: { leaveType, startDate, endDate, days: parsedDays }, idempotencyKey: crypto.randomUUID() },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('me.timeoff.requestLeave')}</h2>
        <Field label={t('me.timeoff.leaveType')} htmlFor="rl-type">
          <div className="flex gap-2">
            {(['ANNUAL', 'UNPAID', 'SICK'] as const).map((lt) => (
              <button
                key={lt}
                type="button"
                onClick={() => setLeaveType(lt)}
                className={cn(
                  'flex-1 rounded-xl border px-3 py-2.5 text-sm font-semibold',
                  leaveType === lt
                    ? 'border-emerald bg-emerald-tint text-emerald-2'
                    : 'border-line text-ink-2 hover:bg-hover',
                )}
              >
                {t(`attendance.leaveType.${lt}`)}
              </button>
            ))}
          </div>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('me.timeoff.startDate')} htmlFor="rl-start">
            <TextInput
              id="rl-start"
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              required
            />
          </Field>
          <Field label={t('me.timeoff.endDate')} htmlFor="rl-end">
            <TextInput
              id="rl-end"
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              required
            />
          </Field>
        </div>
        <Field label={t('me.timeoff.days')} htmlFor="rl-days">
          <TextInput
            id="rl-days"
            type="number"
            min={1}
            value={days}
            onChange={(e) => setDays(e.target.value)}
            required
          />
        </Field>
        {create.isError ? (
          <p className="text-sm text-loss">
            {crossMonth ? t('me.timeoff.crossMonthError') : t('attendance.decide.error')}
          </p>
        ) : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? t('hr.form.saving') : t('common.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}

function LogOvertimeDialog({
  companyId,
  actor,
  onClose,
}: {
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [workDate, setWorkDate] = useState('')
  const [minutes, setMinutes] = useState('60')
  const [dayKind, setDayKind] = useState<'WEEKDAY' | 'REST_DAY'>('WEEKDAY')
  const create = useCreateOvertimeEntry({ companyId, actor })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const parsedMinutes = Number.parseInt(minutes, 10)
    if (!workDate || Number.isNaN(parsedMinutes) || parsedMinutes <= 0) return
    create.mutate(
      { body: { workDate, minutes: parsedMinutes, dayKind }, idempotencyKey: crypto.randomUUID() },
      { onSuccess: onClose },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('me.timeoff.logOvertime')}</h2>
        <Field label={t('me.timeoff.workDate')} htmlFor="ot-date">
          <TextInput
            id="ot-date"
            type="date"
            value={workDate}
            onChange={(e) => setWorkDate(e.target.value)}
            required
          />
        </Field>
        <Field label={t('me.timeoff.minutes')} htmlFor="ot-minutes">
          <TextInput
            id="ot-minutes"
            type="number"
            min={1}
            max={600}
            value={minutes}
            onChange={(e) => setMinutes(e.target.value)}
            required
          />
        </Field>
        <Field label={t('me.timeoff.dayKind')} htmlFor="ot-kind">
          <div className="flex gap-2">
            {(['WEEKDAY', 'REST_DAY'] as const).map((k) => (
              <button
                key={k}
                type="button"
                onClick={() => setDayKind(k)}
                className={cn(
                  'flex-1 rounded-xl border px-3 py-2.5 text-sm font-semibold',
                  dayKind === k
                    ? 'border-emerald bg-emerald-tint text-emerald-2'
                    : 'border-line text-ink-2 hover:bg-hover',
                )}
              >
                {t(`attendance.dayKind.${k}`)}
              </button>
            ))}
          </div>
        </Field>
        {create.isError ? <p className="text-sm text-loss">{t('attendance.decide.error')}</p> : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? t('hr.form.saving') : t('common.submit')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
