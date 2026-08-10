/**
 * ApprovalsScreen — "Menunggu keputusan Anda" (ADR 0049 P5 supervisor surface, staff app). A PUSHED
 * screen (no bottom tab bar) reached from a Beranda card for anyone who can decide their team's
 * requests (see `useIsSupervisor` in ./ui) — the route-level gate lives in App.tsx, this screen just
 * renders. Three SUBMITTED-only queues (claims / leave / overtime) behind a segmented control,
 * mirroring the console's AttendanceTab decision flow (features/hr/AttendanceTab.tsx) but as a
 * phone-first Card list with a bottom-sheet comment dialog per decision, matching this app's own
 * screen idioms (Beranda / TimeoffScreen / ClaimDetailScreen).
 */
import { useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Field } from '@/components/ui/Field'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { EmptyState } from '@/features/_shared/financeUi'
import { DialogOverlay } from '@/features/org/parts'
import {
  useApproveClaim,
  useClaims,
  useRefuseClaim,
  type ExpenseClaimSummary,
} from '@/features/expenses/api'
import { formatDate } from '@/features/expenses/format'
import {
  useApproveLeaveRequest,
  useApproveOvertimeEntry,
  useLeaveRequests,
  useOvertimeEntries,
  useRejectLeaveRequest,
  useRejectOvertimeEntry,
  type LeaveRequestSummary,
  type OvertimeEntrySummary,
} from '@/features/hr/api'
import { formatMoney } from '@/lib/money'
import { localeOf } from '@/i18n'
import { cn } from '@/lib/cn'
import { useTenant } from './ui'

type Tab = 'claims' | 'leave' | 'overtime'
type Action = 'approve' | 'refuse'

interface DecisionTarget {
  tab: Tab
  id: string
  action: Action
}

export function ApprovalsScreen() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const navigate = useNavigate()
  const { companyId, actor } = useTenant()

  const [tab, setTab] = useState<Tab>('claims')
  const [decision, setDecision] = useState<DecisionTarget | null>(null)

  const claims = useClaims({
    companyId,
    actor,
    status: 'SUBMITTED',
    page: 0,
    size: 50,
    enabled: tab === 'claims',
  })
  const leave = useLeaveRequests({
    companyId,
    actor,
    status: 'SUBMITTED',
    page: 0,
    size: 50,
    enabled: tab === 'leave',
  })
  const overtime = useOvertimeEntries({
    companyId,
    actor,
    status: 'SUBMITTED',
    page: 0,
    size: 50,
    enabled: tab === 'overtime',
  })

  return (
    <div className="min-h-[100dvh] bg-paper">
      <ScreenHeader title={t('staff.approvals.title')} onBack={() => navigate('/me')} />

      <div className="flex flex-col gap-3.5 p-4">
        {/* Segmented control */}
        <div className="flex gap-1 rounded-[14px] bg-paper p-1">
          <SegmentButton active={tab === 'claims'} onClick={() => setTab('claims')}>
            {t('staff.approvals.tabClaims')}
          </SegmentButton>
          <SegmentButton active={tab === 'leave'} onClick={() => setTab('leave')}>
            {t('staff.approvals.tabLeave')}
          </SegmentButton>
          <SegmentButton active={tab === 'overtime'} onClick={() => setTab('overtime')}>
            {t('staff.approvals.tabOvertime')}
          </SegmentButton>
        </div>

        {tab === 'claims' ? (
          <ClaimsList
            isLoading={claims.isLoading}
            isError={claims.isError}
            rows={claims.data?.content ?? []}
            locale={locale}
            onDecide={(id, action) => setDecision({ tab: 'claims', id, action })}
          />
        ) : tab === 'leave' ? (
          <LeaveList
            isLoading={leave.isLoading}
            isError={leave.isError}
            rows={leave.data?.content ?? []}
            locale={locale}
            onDecide={(id, action) => setDecision({ tab: 'leave', id, action })}
          />
        ) : (
          <OvertimeList
            isLoading={overtime.isLoading}
            isError={overtime.isError}
            rows={overtime.data?.content ?? []}
            locale={locale}
            onDecide={(id, action) => setDecision({ tab: 'overtime', id, action })}
          />
        )}
      </div>

      {decision ? (
        <DecisionSheet
          companyId={companyId}
          actor={actor}
          target={decision}
          onClose={() => setDecision(null)}
        />
      ) : null}
    </div>
  )
}

/** Pill-toggle segment (Klaim / Cuti / Lembur) — mirrors TimeoffScreen's SegmentButton. */
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
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
        active ? 'bg-surface text-ink shadow-sm' : 'text-ink-3',
      )}
    >
      {children}
    </button>
  )
}

/** The small text-loss error card (mirrors Beranda's `me.error` treatment). */
function ErrorCard() {
  const { t } = useTranslation()
  return (
    <Card className="p-8 text-center text-sm text-loss">
      <TriangleAlert className="mx-auto mb-2 size-5" />
      {t('me.error')}
    </Card>
  )
}

/** One pending-request row — name, meta line, optional amount, Setujui/Tolak actions. */
function ApprovalRow({
  name,
  meta,
  amount,
  onApprove,
  onRefuse,
}: {
  name: string
  meta: string
  amount?: string
  onApprove: () => void
  onRefuse: () => void
}) {
  const { t } = useTranslation()
  return (
    <Card className="flex flex-col gap-3 rounded-[18px] p-[14px]">
      <div className="min-w-0">
        <div className="truncate text-[14px] font-bold text-ink">{name}</div>
        <div className="mt-0.5 text-[12.5px] leading-snug text-ink-3">{meta}</div>
        {amount ? (
          <div className="tnum mt-1.5 font-mono text-[16px] font-bold leading-none text-ink">
            {amount}
          </div>
        ) : null}
      </div>
      <div className="flex gap-2">
        <Button type="button" variant="outline" size="lg" className="flex-1" onClick={onRefuse}>
          {t('staff.approvals.refuse')}
        </Button>
        <Button
          type="button"
          size="lg"
          className="flex-1 bg-brand-600 text-white hover:bg-brand-700"
          onClick={onApprove}
        >
          {t('staff.approvals.approve')}
        </Button>
      </div>
    </Card>
  )
}

function ClaimsList({
  isLoading,
  isError,
  rows,
  locale,
  onDecide,
}: {
  isLoading: boolean
  isError: boolean
  rows: ExpenseClaimSummary[]
  locale: string
  onDecide: (id: string, action: Action) => void
}) {
  const { t } = useTranslation()

  if (isLoading) return <ListSkeleton rows={4} />
  if (isError) return <ErrorCard />
  if (rows.length === 0) {
    return <EmptyState title={t('staff.approvals.empty')} hint={t('staff.approvals.emptyHint')} />
  }

  return (
    <div className="flex flex-col gap-2.5">
      {rows.map((row) => (
        <ApprovalRow
          key={row.id}
          name={row.employeeName}
          meta={`${row.categoryName} · ${formatDate(row.expenseDate, locale)}${
            row.merchant ? ` · ${row.merchant}` : ''
          }`}
          amount={formatMoney(row.amountMinor, row.currency, locale)}
          onApprove={() => onDecide(row.id, 'approve')}
          onRefuse={() => onDecide(row.id, 'refuse')}
        />
      ))}
    </div>
  )
}

function LeaveList({
  isLoading,
  isError,
  rows,
  locale,
  onDecide,
}: {
  isLoading: boolean
  isError: boolean
  rows: LeaveRequestSummary[]
  locale: string
  onDecide: (id: string, action: Action) => void
}) {
  const { t } = useTranslation()

  if (isLoading) return <ListSkeleton rows={4} />
  if (isError) return <ErrorCard />
  if (rows.length === 0) {
    return <EmptyState title={t('staff.approvals.empty')} hint={t('staff.approvals.emptyHint')} />
  }

  return (
    <div className="flex flex-col gap-2.5">
      {rows.map((row) => (
        <ApprovalRow
          key={row.id}
          name={row.employeeName}
          meta={`${t(`staff.timeoff.leaveType.${row.leaveType}`)} · ${formatDate(row.startDate, locale)} – ${formatDate(row.endDate, locale)} · ${row.days} ${t('staff.daysUnit')}`}
          onApprove={() => onDecide(row.id, 'approve')}
          onRefuse={() => onDecide(row.id, 'refuse')}
        />
      ))}
    </div>
  )
}

function OvertimeList({
  isLoading,
  isError,
  rows,
  locale,
  onDecide,
}: {
  isLoading: boolean
  isError: boolean
  rows: OvertimeEntrySummary[]
  locale: string
  onDecide: (id: string, action: Action) => void
}) {
  const { t } = useTranslation()

  if (isLoading) return <ListSkeleton rows={4} />
  if (isError) return <ErrorCard />
  if (rows.length === 0) {
    return <EmptyState title={t('staff.approvals.empty')} hint={t('staff.approvals.emptyHint')} />
  }

  return (
    <div className="flex flex-col gap-2.5">
      {rows.map((row) => (
        <ApprovalRow
          key={row.id}
          name={row.employeeName}
          meta={`${t(`staff.timeoff.dayKind.${row.dayKind}`)} · ${formatDate(row.workDate, locale)} · ${(
            row.minutes / 60
          ).toLocaleString(locale)} ${t('staff.timeoff.hoursUnit')}`}
          onApprove={() => onDecide(row.id, 'approve')}
          onRefuse={() => onDecide(row.id, 'refuse')}
        />
      ))}
    </div>
  )
}

/**
 * The comment bottom sheet for one decision. Calls every mutation hook unconditionally (mirrors
 * AttendanceTab's `DecideDialog`) and branches on `target.tab`/`target.action` inside the submit
 * handler — hooks can't be called conditionally. `idempotencyKey` is minted once, inside the
 * handler, only once validation passes (never inside a `mutationFn`, which TanStack Query may retry).
 */
function DecisionSheet({
  companyId,
  actor,
  target,
  onClose,
}: {
  companyId: string
  actor: string
  target: DecisionTarget
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [comment, setComment] = useState('')
  const [attempted, setAttempted] = useState(false)

  const approveClaim = useApproveClaim({ companyId, actor })
  const refuseClaim = useRefuseClaim({ companyId, actor })
  const approveLeave = useApproveLeaveRequest({ companyId, actor })
  const rejectLeave = useRejectLeaveRequest({ companyId, actor })
  const approveOvertime = useApproveOvertimeEntry({ companyId, actor })
  const rejectOvertime = useRejectOvertimeEntry({ companyId, actor })

  const isRefuse = target.action === 'refuse'
  const pending =
    approveClaim.isPending ||
    refuseClaim.isPending ||
    approveLeave.isPending ||
    rejectLeave.isPending ||
    approveOvertime.isPending ||
    rejectOvertime.isPending
  const isError =
    approveClaim.isError ||
    refuseClaim.isError ||
    approveLeave.isError ||
    rejectLeave.isError ||
    approveOvertime.isError ||
    rejectOvertime.isError

  const missingReason = isRefuse && comment.trim() === ''

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (missingReason) {
      setAttempted(true)
      return
    }
    const idempotencyKey = crypto.randomUUID()
    if (target.tab === 'claims') {
      if (isRefuse) {
        refuseClaim.mutate({ id: target.id, comment, idempotencyKey }, { onSuccess: onClose })
      } else {
        approveClaim.mutate(
          { id: target.id, comment: comment || undefined, idempotencyKey },
          { onSuccess: onClose },
        )
      }
    } else if (target.tab === 'leave') {
      if (isRefuse) {
        rejectLeave.mutate(
          { requestId: target.id, note: comment, idempotencyKey },
          { onSuccess: onClose },
        )
      } else {
        approveLeave.mutate(
          { requestId: target.id, note: comment || undefined, idempotencyKey },
          { onSuccess: onClose },
        )
      }
    } else if (isRefuse) {
      rejectOvertime.mutate(
        { entryId: target.id, note: comment, idempotencyKey },
        { onSuccess: onClose },
      )
    } else {
      approveOvertime.mutate(
        { entryId: target.id, note: comment || undefined, idempotencyKey },
        { onSuccess: onClose },
      )
    }
  }

  return (
    <DialogOverlay onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {t(isRefuse ? 'staff.approvals.refuseTitle' : 'staff.approvals.approveTitle')}
        </h2>
        <Field
          label={t(isRefuse ? 'staff.approvals.commentRequired' : 'staff.approvals.commentOptional')}
          htmlFor="approval-comment"
          error={attempted && missingReason ? t('staff.approvals.refuseNeedsReason') : undefined}
        >
          <textarea
            id="approval-comment"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            rows={3}
            maxLength={4000}
            className="w-full rounded-xl border border-line bg-surface px-3.5 py-3 text-sm text-ink focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
          />
        </Field>
        {isError ? <p className="text-sm text-loss">{t('me.error')}</p> : null}
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            type="submit"
            disabled={pending}
            className={isRefuse ? undefined : 'bg-brand-600 text-white hover:bg-brand-700'}
          >
            {pending
              ? t('staff.approvals.submitting')
              : t(isRefuse ? 'staff.approvals.confirmRefuse' : 'staff.approvals.confirmApprove')}
          </Button>
        </div>
      </form>
    </DialogOverlay>
  )
}
