/**
 * Cuti & lembur — the staff-app leave & overtime tab (ADR 0049 P5 redesign). Reuses the console's
 * /me time-off hooks unchanged: the caller's own derived annual-leave balance, a segmented list of
 * their own leave requests and overtime entries, and cancel for anything still SUBMITTED. Request
 * forms live at their own routes (/me/timeoff/new-leave, /me/timeoff/new-overtime) — not built here.
 */
import { useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Skeleton } from '@/components/ui/Skeleton'
import { formatDate } from '@/features/expenses/format'
import { localeOf } from '@/i18n'
import {
  useCancelLeaveRequest,
  useCancelOvertimeEntry,
  useMyLeaveBalance,
  useMyLeaveRequests,
  useMyOvertimeEntries,
  type MyLeaveRequest,
  type MyOvertimeEntry,
} from '@/features/me/api'
import { cn } from '@/lib/cn'
import { SectionLabel, StaffHeader, TimeoffStatusBadge, useTenant } from './ui'

type Segment = 'leave' | 'overtime'

export function TimeoffScreen() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const { companyId, actor } = useTenant()
  const year = new Date().getFullYear()
  const [segment, setSegment] = useState<Segment>('leave')

  const balance = useMyLeaveBalance({ companyId, actor, year, enabled: true })
  const leave = useMyLeaveRequests({ companyId, actor, enabled: true })
  const overtime = useMyOvertimeEntries({ companyId, actor, enabled: true })
  const cancelLeave = useCancelLeaveRequest({ companyId, actor })
  const cancelOvertime = useCancelOvertimeEntry({ companyId, actor })

  if (balance.isLoading) {
    return (
      <>
        <StaffHeader title={t('staff.timeoff.title')} />
        <div className="flex flex-col gap-3.5 p-4">
          <Skeleton className="h-[168px] rounded-[20px]" />
          <div className="flex gap-2.5">
            <Skeleton className="h-11 flex-1 rounded-[14px]" />
            <Skeleton className="h-11 flex-1 rounded-[14px]" />
          </div>
          <Skeleton className="h-11 rounded-[14px]" />
          <Skeleton className="h-[74px] rounded-[18px]" />
          <Skeleton className="h-[74px] rounded-[18px]" />
        </div>
      </>
    )
  }

  if (balance.isError || !balance.data) {
    return (
      <>
        <StaffHeader title={t('staff.timeoff.title')} />
        <Card className="m-4 mt-6 p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('me.error')}
        </Card>
      </>
    )
  }

  const b = balance.data
  const total = b.grantedDays + b.adjustmentDays
  const usedFrac = total > 0 ? Math.min(1, b.usedDays / total) : 0

  return (
    <>
      <StaffHeader title={t('staff.timeoff.title')} />
      <div className="flex flex-col gap-3.5 p-4">
        {/* Balance */}
        <Card className="p-[18px]">
          <SectionLabel>{t('staff.timeoff.balanceTitle', { year })}</SectionLabel>
          <div className="mt-3 grid grid-cols-4 gap-2">
            <BalanceTile label={t('staff.timeoff.entitled')} value={b.grantedDays} locale={locale} />
            <BalanceTile
              label={t('staff.timeoff.adjustment')}
              value={b.adjustmentDays}
              locale={locale}
              signed
            />
            <BalanceTile label={t('staff.timeoff.used')} value={b.usedDays} locale={locale} />
            <BalanceTile
              label={t('staff.timeoff.remaining')}
              value={b.remaining}
              locale={locale}
              tone="profit"
            />
          </div>
          <div className="mt-4 h-[6px] overflow-hidden rounded-full bg-paper">
            <div className="h-full rounded-full bg-brand-600" style={{ width: `${usedFrac * 100}%` }} />
          </div>
          <p className="mt-2 text-[11.5px] leading-snug text-ink-3">
            {t('staff.timeoff.usedOf', { used: b.usedDays, total })}
          </p>
        </Card>

        {/* Actions */}
        <div className="flex gap-2.5">
          <Link
            to="/me/timeoff/new-leave"
            viewTransition
            className="flex h-11 flex-1 items-center justify-center rounded-[14px] bg-brand-600 text-[14px] font-bold text-white transition-transform active:scale-[0.98]"
          >
            {t('staff.timeoff.requestLeave')}
          </Link>
          <Link
            to="/me/timeoff/new-overtime"
            viewTransition
            className="flex h-11 flex-1 items-center justify-center rounded-[14px] border border-line bg-surface text-[14px] font-bold text-ink transition-transform active:scale-[0.98]"
          >
            {t('staff.timeoff.logOvertime')}
          </Link>
        </div>

        {/* Segmented control */}
        <div className="flex gap-1 rounded-[14px] bg-paper p-1">
          <SegmentButton active={segment === 'leave'} onClick={() => setSegment('leave')}>
            {t('staff.timeoff.segLeave')}
          </SegmentButton>
          <SegmentButton active={segment === 'overtime'} onClick={() => setSegment('overtime')}>
            {t('staff.timeoff.segOvertime')}
          </SegmentButton>
        </div>

        {/* List */}
        {segment === 'leave' ? (
          <LeaveList
            requests={leave.data ?? []}
            isLoading={leave.isLoading}
            locale={locale}
            onCancel={(requestId) =>
              cancelLeave.mutate({ requestId, idempotencyKey: crypto.randomUUID() })
            }
            cancelling={cancelLeave.isPending}
          />
        ) : (
          <OvertimeList
            entries={overtime.data ?? []}
            isLoading={overtime.isLoading}
            locale={locale}
            onCancel={(entryId) =>
              cancelOvertime.mutate({ entryId, idempotencyKey: crypto.randomUUID() })
            }
            cancelling={cancelOvertime.isPending}
          />
        )}

        <p className="pt-1 text-center text-[11.5px] leading-snug text-ink-3">
          {t('staff.timeoff.cancelHint')}
        </p>
      </div>
    </>
  )
}

/** One label + big mono number cell in the balance card's 4-col grid. */
function BalanceTile({
  label,
  value,
  locale,
  tone,
  signed = false,
}: {
  label: string
  value: number
  locale: string
  tone?: 'profit'
  signed?: boolean
}) {
  const formatted = new Intl.NumberFormat(
    locale,
    signed ? { signDisplay: 'exceptZero' } : undefined,
  ).format(value)
  return (
    <div className="min-w-0">
      <div className="truncate text-[11px] font-medium text-ink-3">{label}</div>
      <div
        className={cn(
          'tnum mt-1 font-mono text-[18px] font-bold leading-none',
          tone === 'profit' ? 'text-profit-ink' : 'text-ink',
        )}
      >
        {formatted}
      </div>
    </div>
  )
}

/** Pill-toggle segment (Cuti / Lembur) — mirrors the mockup's segmented control. */
function SegmentButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'flex h-11 flex-1 items-center justify-center rounded-[11px] text-[13.5px] font-bold transition-colors',
        active ? 'bg-surface text-ink shadow-sm' : 'text-ink-3',
      )}
    >
      {children}
    </button>
  )
}

/** A "Batalkan pengajuan" button — only rendered for SUBMITTED rows, ≥44px tap target. */
function CancelButton({ onClick, disabled }: { onClick: () => void; disabled: boolean }) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="flex h-11 items-center justify-center self-start rounded-[12px] border border-line px-4 text-[13px] font-bold text-ink-3 transition-colors hover:border-loss hover:text-loss disabled:opacity-50"
    >
      {t('staff.timeoff.cancel')}
    </button>
  )
}

function LeaveList({
  requests,
  isLoading,
  locale,
  onCancel,
  cancelling,
}: {
  requests: MyLeaveRequest[]
  isLoading: boolean
  locale: string
  onCancel: (requestId: string) => void
  cancelling: boolean
}) {
  const { t } = useTranslation()

  if (isLoading) {
    return (
      <div className="flex flex-col gap-2.5">
        <Skeleton className="h-[74px] rounded-[18px]" />
        <Skeleton className="h-[74px] rounded-[18px]" />
      </div>
    )
  }

  if (requests.length === 0) {
    return (
      <Card className="p-6 text-center text-[13px] text-ink-3">{t('staff.timeoff.emptyLeave')}</Card>
    )
  }

  return (
    <div className="flex flex-col gap-2.5">
      {requests.map((r) => (
        <Card key={r.id} className="flex flex-col gap-2 rounded-[18px] p-[14px]">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[14px] font-bold text-ink">
              {t(`staff.timeoff.leaveType.${r.leaveType}`)}
            </span>
            <TimeoffStatusBadge status={r.status} />
          </div>
          <div className="text-[12.5px] text-ink-3">
            {formatDate(r.startDate, locale)} – {formatDate(r.endDate, locale)} · {r.days}{' '}
            {t('staff.daysUnit')}
          </div>
          {r.status === 'SUBMITTED' ? (
            <CancelButton onClick={() => onCancel(r.id)} disabled={cancelling} />
          ) : null}
        </Card>
      ))}
    </div>
  )
}

function OvertimeList({
  entries,
  isLoading,
  locale,
  onCancel,
  cancelling,
}: {
  entries: MyOvertimeEntry[]
  isLoading: boolean
  locale: string
  onCancel: (entryId: string) => void
  cancelling: boolean
}) {
  const { t } = useTranslation()

  if (isLoading) {
    return (
      <div className="flex flex-col gap-2.5">
        <Skeleton className="h-[74px] rounded-[18px]" />
        <Skeleton className="h-[74px] rounded-[18px]" />
      </div>
    )
  }

  if (entries.length === 0) {
    return (
      <Card className="p-6 text-center text-[13px] text-ink-3">{t('staff.timeoff.emptyOvertime')}</Card>
    )
  }

  return (
    <div className="flex flex-col gap-2.5">
      {entries.map((e) => (
        <Card key={e.id} className="flex flex-col gap-2 rounded-[18px] p-[14px]">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[14px] font-bold text-ink">
              {t(`staff.timeoff.dayKind.${e.dayKind}`)}
            </span>
            <TimeoffStatusBadge status={e.status} />
          </div>
          <div className="text-[12.5px] text-ink-3">
            {formatDate(e.workDate, locale)} · {(e.minutes / 60).toLocaleString(locale)}{' '}
            {t('staff.timeoff.hoursUnit')}
          </div>
          {e.status === 'SUBMITTED' ? (
            <CancelButton onClick={() => onCancel(e.id)} disabled={cancelling} />
          ) : null}
        </Card>
      ))}
    </div>
  )
}
