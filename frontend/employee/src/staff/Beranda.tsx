/**
 * Beranda — the staff-app home (ADR 0049 P5 redesign). Answers "what needs me" first: draft claims
 * never sent and leave still awaiting a decision lead, above pay and balances (both derivable from
 * data /me already loads). Then this month's sales, then quick stats. All figures are the caller's
 * own (rule 6 masking handled on the profile screen). Reuses the console's /me hooks unchanged.
 */
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  CalendarClock,
  CheckCircle2,
  ChevronRight,
  ClipboardCheck,
  FileText,
  TriangleAlert,
  UserRound,
} from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Card } from '@/components/ui/Card'
import { Skeleton } from '@/components/ui/Skeleton'
import { EmptyState } from '@/features/_shared/financeUi'
import { useClaims, useMyClaims } from '@/features/expenses/api'
import { formatDate } from '@/features/expenses/format'
import { useLeaveRequests, useOvertimeEntries } from '@/features/hr/api'
import { formatMoney, formatPercent } from '@/lib/money'
import { localeOf } from '@/i18n'
import {
  isNotLinked,
  useMyLeaveBalance,
  useMyLeaveRequests,
  useMyPayslip,
  useMyPayslips,
  useMyProfile,
  useMySales,
} from '@/features/me/api'
import { SectionLabel, greetingKey, useIsSupervisor, useTenant } from './ui'

export function Beranda() {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const { companyId, actor } = useTenant()
  const year = new Date().getFullYear()

  const profile = useMyProfile({ companyId, actor, enabled: true })
  const notLinked = isNotLinked(profile.error)
  const claims = useMyClaims({ companyId, actor, page: 0, enabled: true })
  const leave = useMyLeaveRequests({ companyId, actor, enabled: true })
  const balance = useMyLeaveBalance({ companyId, actor, year, enabled: true })
  const sales = useMySales({ companyId, actor, enabled: true })
  const payslips = useMyPayslips({ companyId, actor, enabled: true })

  // Supervisor approvals (ADR 0049 P5 — "one app, two roles"): pending counts for the home card,
  // fetched only for a supervisor login (the gateway is the real boundary — see rolePreset).
  const isSup = useIsSupervisor()
  const supClaims = useClaims({ companyId, actor, status: 'SUBMITTED', page: 0, size: 50, enabled: isSup })
  const supLeave = useLeaveRequests({ companyId, actor, status: 'SUBMITTED', page: 0, size: 50, enabled: isSup })
  const supOvertime = useOvertimeEntries({
    companyId,
    actor,
    status: 'SUBMITTED',
    page: 0,
    size: 50,
    enabled: isSup,
  })

  const latestSlip = [...(payslips.data ?? [])].sort((a, b) => b.period.localeCompare(a.period))[0]
  const latestDetail = useMyPayslip({
    companyId,
    actor,
    runId: latestSlip?.runId ?? null,
    enabled: !!latestSlip,
  })

  const draftCount = (claims.data?.content ?? []).filter((c) => c.status === 'DRAFT').length
  const pendingLeaveCount = (leave.data ?? []).filter((r) => r.status === 'SUBMITTED').length

  if (profile.isLoading) {
    return (
      <>
        <div className="flex items-center gap-3 border-b border-line bg-surface px-[18px] pb-5 pt-[18px]">
          <Skeleton className="size-[50px] shrink-0 rounded-[17px]" />
          <div className="min-w-0 flex-1">
            <Skeleton className="h-3.5 w-20" />
            <Skeleton className="mt-1.5 h-5 w-40" />
          </div>
        </div>
        <div className="flex flex-col gap-3 p-4">
          <Skeleton className="h-[66px] rounded-[18px]" />
          <Skeleton className="h-[66px] rounded-[18px]" />
          <Skeleton className="h-40 rounded-[20px]" />
          <div className="grid grid-cols-2 gap-2.5">
            <Skeleton className="h-20 rounded-[18px]" />
            <Skeleton className="h-20 rounded-[18px]" />
          </div>
        </div>
      </>
    )
  }

  if (notLinked) {
    return (
      <div className="p-4 pt-6">
        <EmptyState title={t('me.notLinked.title')} hint={t('me.notLinked.hint')} />
      </div>
    )
  }

  if (profile.isError || !profile.data) {
    return (
      <Card className="m-4 mt-6 p-8 text-center text-sm text-loss">
        <TriangleAlert className="mx-auto mb-2 size-5" />
        {t('me.error')}
      </Card>
    )
  }

  const p = profile.data

  return (
    <>
      {/* Greeting header */}
      <div className="border-b border-line bg-surface px-[18px] pb-5 pt-[18px]">
        <div className="flex items-center gap-3">
          <Link
            to="/me/profile"
            viewTransition
            aria-label={t('staff.profile.title')}
            className="grid size-[50px] shrink-0 place-items-center rounded-[17px] bg-brand-50 text-brand-700 transition-transform active:scale-95"
          >
            <UserRound className="size-[25px]" strokeWidth={1.8} aria-hidden />
          </Link>
          <div className="min-w-0 flex-1">
            <div className="text-[12.5px] font-medium leading-tight text-ink-3">
              {t(`staff.greeting.${greetingKey()}`)}
            </div>
            <div className="mt-0.5 truncate text-[20px] font-bold leading-tight tracking-[-0.015em] text-ink">
              {p.fullName}
            </div>
          </div>
          <Badge tone={p.status === 'ACTIVE' ? 'profit' : 'amber'}>
            {t(p.status === 'ACTIVE' ? 'me.profile.active' : 'me.profile.inactive')}
          </Badge>
        </div>
        {p.assignments.length > 0 ? (
          <div className="mt-3 flex flex-wrap gap-1.5">
            {p.assignments.map((a) => (
              <span
                key={a.id}
                className="rounded-full border border-line bg-paper px-2.5 py-[5px] text-[12px] font-medium text-ink-3"
              >
                <span className="font-bold text-ink">{a.role}</span> · {formatDate(a.effectiveFrom, locale)}
              </span>
            ))}
          </div>
        ) : null}
      </div>

      {/* Needs action */}
      <section className="px-4 pt-[18px]">
        <SectionLabel className="pl-1">{t('staff.needsAction')}</SectionLabel>
        <div className="mt-2 flex flex-col gap-2.5">
          {draftCount > 0 ? (
            <TodoCard
              to="/me/expenses"
              icon={<FileText className="size-[19px]" strokeWidth={1.9} aria-hidden />}
              tone="brand"
              title={t('staff.todo.draftClaim.title', { count: draftCount })}
              meta={t('staff.todo.draftClaim.meta', { count: draftCount })}
            />
          ) : null}
          {pendingLeaveCount > 0 ? (
            <TodoCard
              to="/me/timeoff"
              icon={<CalendarClock className="size-[19px]" strokeWidth={1.9} aria-hidden />}
              tone="amber"
              title={t('staff.todo.pendingLeave.title', { count: pendingLeaveCount })}
              meta={t('staff.todo.pendingLeave.meta', { count: pendingLeaveCount })}
            />
          ) : null}
          {draftCount === 0 && pendingLeaveCount === 0 ? (
            <Card className="flex items-center gap-3 p-4">
              <span className="grid size-[38px] shrink-0 place-items-center rounded-[13px] bg-tint-profit text-profit-ink">
                <CheckCircle2 className="size-[19px]" strokeWidth={1.9} aria-hidden />
              </span>
              <div className="min-w-0">
                <div className="text-[14px] font-bold text-ink">{t('staff.allClear.title')}</div>
                <div className="mt-0.5 text-[12.5px] text-ink-3">{t('staff.allClear.hint')}</div>
              </div>
            </Card>
          ) : null}
        </div>
      </section>

      {/* Supervisor approvals (ADR 0049 P5 — "one app, two roles") */}
      {isSup ? (
        <section className="px-4 pt-[18px]">
          <Link
            to="/me/approvals"
            viewTransition
            className="block rounded-[20px] border border-brand-100 bg-brand-50 p-[18px] transition-transform active:scale-[0.99]"
          >
            <div className="flex items-center gap-2.5">
              <ClipboardCheck className="size-[19px] shrink-0 text-brand-700" strokeWidth={1.9} aria-hidden />
              <span className="text-[15px] font-bold text-brand-700">{t('staff.approvals.home')}</span>
              <ChevronRight className="ml-auto size-[18px] shrink-0 text-brand-700" aria-hidden />
            </div>
            <div className="mt-3 grid grid-cols-3 gap-2">
              <ApprovalStat n={supClaims.data?.totalElements ?? 0} label={t('staff.approvals.tabClaims')} />
              <ApprovalStat n={supLeave.data?.totalElements ?? 0} label={t('staff.approvals.tabLeave')} />
              <ApprovalStat n={supOvertime.data?.totalElements ?? 0} label={t('staff.approvals.tabOvertime')} />
            </div>
          </Link>
        </section>
      ) : null}

      {/* This month — sales + commission (same null rule as the console SalesSection) */}
      {sales.data != null &&
      (sales.data.salesMinor !== 0 || sales.data.commissionBasisPoints !== null) ? (
        <section className="px-4 pt-[18px]">
          <SectionLabel className="pl-1">{t('staff.thisMonth')}</SectionLabel>
          <Card className="mt-2 p-[18px]">
            <div className="text-[12.5px] font-medium text-ink-3">{t('me.sales.title')}</div>
            <div className="tnum mt-1.5 font-mono text-[28px] font-bold leading-none tracking-[-0.02em] text-ink">
              {formatMoney(sales.data.salesMinor, sales.data.currency, locale)}
            </div>
            {sales.data.commissionBasisPoints !== null ? (
              <>
                <div className="mt-3.5 flex gap-2.5">
                  <div className="flex-1 rounded-[14px] bg-paper px-3.5 py-3">
                    <div className="text-[11.5px] font-medium text-ink-3">{t('me.sales.rate')}</div>
                    <div className="tnum mt-1 font-mono text-[16px] font-bold leading-none text-ink">
                      {formatPercent(sales.data.commissionBasisPoints / 10000, locale)}
                    </div>
                  </div>
                  <div className="flex-1 rounded-[14px] bg-tint-profit px-3.5 py-3">
                    <div className="text-[11.5px] font-medium text-profit-ink">
                      {t('me.sales.estimate')}
                    </div>
                    <div className="tnum mt-1 font-mono text-[16px] font-bold leading-none text-profit-ink">
                      {formatMoney(sales.data.commissionEstimateMinor ?? 0, sales.data.currency, locale)}
                    </div>
                  </div>
                </div>
                <p className="mt-2.5 text-[11.5px] leading-snug text-ink-3">
                  {t('me.sales.estimateHint')}
                </p>
              </>
            ) : null}
          </Card>
        </section>
      ) : null}

      {/* Quick stats */}
      <section className="grid grid-cols-2 gap-2.5 px-4 pt-3.5">
        <Link
          to="/me/timeoff"
          viewTransition
          className="rounded-[18px] border border-line bg-surface p-4 shadow-sm transition-transform active:scale-[0.98]"
        >
          <div className="text-[11.5px] font-medium text-ink-3">
            {t('staff.leaveRemaining', { year })}
          </div>
          <div className="mt-1.5 flex items-baseline gap-1.5">
            <span className="tnum font-mono text-[27px] font-bold leading-none text-ink">
              {balance.isLoading ? '…' : (balance.data?.remaining ?? '—')}
            </span>
            <span className="text-[13px] font-medium text-ink-3">{t('staff.daysUnit')}</span>
          </div>
        </Link>
        <Link
          to="/me/payslips"
          viewTransition
          className="rounded-[18px] border border-line bg-surface p-4 shadow-sm transition-transform active:scale-[0.98]"
        >
          <div className="truncate text-[11.5px] font-medium text-ink-3">
            {latestSlip
              ? t('staff.lastPayslip', { month: monthLabel(latestSlip.period, locale) })
              : t('mobile.tabs.payslips')}
          </div>
          <div className="tnum mt-1.5 font-mono text-[17px] font-bold leading-tight text-ink">
            {latestDetail.data
              ? formatMoney(latestDetail.data.netMinor, latestDetail.data.currency, locale)
              : '—'}
          </div>
        </Link>
      </section>

      <p className="whitespace-pre-line px-4 pt-5 text-center text-[11.5px] leading-snug text-ink-3">
        {t('staff.dataPrivate')}
      </p>
    </>
  )
}

/** A "needs action" row — icon tile, title, one-line meta, chevron; links into the owning tab. */
function TodoCard({
  to,
  icon,
  tone,
  title,
  meta,
}: {
  to: string
  icon: React.ReactNode
  tone: 'brand' | 'amber'
  title: string
  meta: string
}) {
  return (
    <Link
      to={to}
      viewTransition
      className="flex items-center gap-3 rounded-[18px] border border-line bg-surface p-3.5 shadow-sm transition-colors hover:border-brand-100 hover:bg-brand-50/40 active:scale-[0.99]"
    >
      <span
        className={
          tone === 'brand'
            ? 'grid size-[38px] shrink-0 place-items-center rounded-[13px] bg-brand-50 text-brand-700'
            : 'grid size-[38px] shrink-0 place-items-center rounded-[13px] bg-tint-warning text-amber-2'
        }
      >
        {icon}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-[14px] font-bold leading-tight text-ink">{title}</span>
        <span className="mt-0.5 block text-[12.5px] leading-snug text-ink-3">{meta}</span>
      </span>
      <ChevronRight className="size-[18px] shrink-0 text-ink-300" aria-hidden />
    </Link>
  )
}

/** A count tile in the supervisor approvals card. */
function ApprovalStat({ n, label }: { n: number; label: string }) {
  return (
    <div className="rounded-[14px] bg-surface px-3 py-2.5">
      <div className="tnum font-mono text-[22px] font-bold leading-none text-ink">{n}</div>
      <div className="mt-1 text-[11.5px] font-medium text-ink-3">{label}</div>
    </div>
  )
}

/** 'YYYY-MM' → a localized month label (e.g. "Juli" / "July"). Falls back to the raw period. */
function monthLabel(period: string, locale: string): string {
  const d = new Date(`${period}-01T00:00:00`)
  if (Number.isNaN(d.getTime())) return period
  return new Intl.DateTimeFormat(locale, { month: 'long' }).format(d)
}
