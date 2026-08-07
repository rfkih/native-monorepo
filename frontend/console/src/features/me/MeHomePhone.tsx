/**
 * MeHomePhone — the phone "Beranda" of the employee self-service surface (Native Console
 * Android design). Rendered by Me.tsx below the 640px cutoff INSTEAD of the long desktop
 * page; the sections it links to are their own phone screens (/me/payslips, /me/timeoff,
 * /me/expenses). Same hooks, same masking rules (rule 6), same not-linked handling as the
 * desktop page — only the layout is phone-shaped.
 */

import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { CalendarDays, Clock, LogOut, Plus, ReceiptText, TriangleAlert, UserRound } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { EmptyState } from '@/features/_shared/financeUi'
import { useMyClaims } from '@/features/expenses/api'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { formatMoney } from '@/lib/money'
import { localeOf } from '@/i18n'
import { isNotLinked, useMyLeaveBalance, useMyProfile, useMySales } from './api'

function MicroLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="font-mono text-[11px] font-semibold uppercase tracking-[0.06em] text-ink-3">
      {children}
    </div>
  )
}

/** A tappable stat card: micro-label, big mono figure, unit — links into the owning screen. */
function StatCard({
  to,
  label,
  value,
  unit,
}: {
  to: string
  label: string
  value: string
  unit: string
}) {
  return (
    <Link to={to} className="block rounded-[18px] border border-line bg-surface p-4 shadow-sm">
      <MicroLabel>{label}</MicroLabel>
      <div className="mt-1.5 flex items-baseline gap-1.5">
        <span className="tnum font-mono text-[27px] font-bold leading-none text-ink">{value}</span>
        <span className="text-[13px] font-medium text-ink-3">{unit}</span>
      </div>
    </Link>
  )
}

export function MeHomePhone() {
  const { t, i18n } = useTranslation()
  const auth = useAuth()
  const locale = localeOf(i18n.language)
  const companyId = auth.companyId ?? 'me'
  const actor = auth.actor
  const canPos = hasAnyRole(auth.roles, 'owner', 'manager', 'cashier')
  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')

  const profile = useMyProfile({ companyId, actor, enabled: true })
  const notLinked = isNotLinked(profile.error)
  const balance = useMyLeaveBalance({
    companyId,
    actor,
    year: new Date().getFullYear(),
    enabled: true,
  })
  const claims = useMyClaims({ companyId, actor, page: 0, enabled: true })
  const sales = useMySales({ companyId, actor, enabled: true })

  // Same pending approximation as the desktop ExpensesCard (first page only — fine for a badge).
  const pendingCount = (claims.data?.content ?? []).filter(
    (c) => c.status === 'DRAFT' || c.status === 'SUBMITTED',
  ).length

  const quickActions = [
    { key: 'payslips', to: '/me/payslips', icon: ReceiptText, label: t('mobile.tabs.payslips') },
    { key: 'leave', to: '/me/timeoff', icon: CalendarDays, label: t('me.timeoff.requestLeave') },
    { key: 'overtime', to: '/me/timeoff', icon: Clock, label: t('me.timeoff.logOvertime') },
    { key: 'claim', to: '/me/expenses', icon: Plus, label: t('me.expenses.newClaim') },
  ]

  // Bottom clearance for the fixed tab bar comes from MobileTabBarGate's in-flow spacer.
  return (
    <div className="min-h-screen bg-paper pb-6">
      {/* Compact topbar — mirrors the desktop Me header (POS/dashboard escape + logout). */}
      <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b border-line bg-surface/90 px-4 backdrop-blur">
        <Wordmark />
        <div className="flex-1" />
        {canDashboard ? (
          <Link to="/" className="rounded-xl px-2 py-1.5 text-sm font-medium text-ink-3 hover:text-ink">
            {t('me.toDashboard')}
          </Link>
        ) : canPos ? (
          <Link to="/pos" className="rounded-xl px-2 py-1.5 text-sm font-medium text-ink-3 hover:text-ink">
            {t('me.toPos')}
          </Link>
        ) : null}
        <LanguageSwitcher />
        {AUTH_MODE === 'oidc' && auth.authenticated ? (
          <button
            type="button"
            onClick={auth.logout}
            title={auth.actor}
            aria-label={t('nav.logout')}
            className="grid size-11 place-items-center rounded-full text-ink-3 hover:bg-hover hover:text-ink"
          >
            <LogOut className="size-[18px]" aria-hidden />
          </button>
        ) : null}
      </header>

      {profile.isLoading ? (
        <Card className="m-4 p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : notLinked ? (
        <div className="p-4">
          <EmptyState title={t('me.notLinked.title')} hint={t('me.notLinked.hint')} />
        </div>
      ) : profile.isError || !profile.data ? (
        <Card className="m-4 p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('me.error')}
        </Card>
      ) : (
        <>
          {/* Profile header */}
          <div className="border-b border-line bg-surface px-5 pb-4 pt-3.5">
            <div className="flex items-center gap-3">
              <span className="grid size-[52px] shrink-0 place-items-center rounded-[18px] bg-emerald-tint text-emerald-2">
                <UserRound className="size-[26px]" aria-hidden />
              </span>
              <div className="min-w-0 flex-1">
                <div className="truncate text-[19px] font-bold leading-tight tracking-[-0.01em] text-ink">
                  {profile.data.fullName}
                </div>
                <div className="mt-0.5 truncate text-[13px] font-medium text-ink-3">
                  {profile.data.assignments.length > 0
                    ? profile.data.assignments.map((a) => a.role).join(' · ')
                    : t('me.assignments.empty')}
                </div>
              </div>
              <Badge tone={profile.data.status === 'ACTIVE' ? 'emerald' : 'amber'}>
                {t(profile.data.status === 'ACTIVE' ? 'me.profile.active' : 'me.profile.inactive')}
              </Badge>
            </div>
          </div>

          <div className="flex flex-col gap-3.5 px-4 pt-4">
            {/* Stat cards */}
            <div className="grid grid-cols-2 gap-2.5">
              <StatCard
                to="/me/timeoff"
                label={t('me.home.leaveRemaining')}
                value={balance.isLoading ? '…' : String(balance.data?.remaining ?? '—')}
                unit={t('me.home.daysUnit')}
              />
              <StatCard
                to="/me/expenses"
                label={t('me.home.claimsPending')}
                value={claims.isLoading ? '…' : String(pendingCount)}
                unit={t('me.home.claimsUnit')}
              />
            </div>

            {/* Sales + commission — same null rule as the desktop SalesSection. */}
            {sales.data != null &&
            (sales.data.salesMinor !== 0 || sales.data.commissionBasisPoints !== null) ? (
              <Card className="p-[18px]">
                <MicroLabel>{t('me.sales.title')}</MicroLabel>
                <div className="tnum mt-1.5 font-mono text-[26px] font-bold leading-none tracking-[-0.02em] text-ink">
                  {formatMoney(sales.data.salesMinor, sales.data.currency, locale)}
                </div>
                {sales.data.commissionBasisPoints !== null ? (
                  <>
                    <div className="mt-3.5 flex gap-2.5">
                      <div className="flex-1 rounded-[14px] bg-paper px-3.5 py-3">
                        <div className="text-[11px] font-medium text-ink-3">{t('me.sales.rate')}</div>
                        <div className="tnum mt-1 font-mono text-[16px] font-bold leading-none text-ink">
                          {(sales.data.commissionBasisPoints / 100).toLocaleString(locale)}%
                        </div>
                      </div>
                      <div className="flex-1 rounded-[14px] bg-tint-profit px-3.5 py-3">
                        <div className="text-[11px] font-medium text-profit-ink">
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
            ) : null}

            {/* Quick actions */}
            <div className="grid grid-cols-2 gap-2.5">
              {quickActions.map((a) => {
                const ActionIcon = a.icon
                return (
                  <Link
                    key={a.key}
                    to={a.to}
                    className="flex min-h-[60px] items-center gap-3 rounded-2xl border border-line bg-surface px-3.5 py-3 text-[13.5px] font-semibold leading-tight text-ink transition-colors hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2"
                  >
                    <ActionIcon className="size-5 shrink-0 text-emerald-2" strokeWidth={1.9} aria-hidden />
                    {a.label}
                  </Link>
                )
              })}
            </div>

            {/* Personal data (rule 6 — masked, never logged) */}
            <section>
              <div className="pl-1">
                <MicroLabel>{t('me.home.personalData')}</MicroLabel>
              </div>
              <Card className="mt-2 overflow-hidden rounded-[18px] p-0">
                {[
                  { key: 'nik', label: t('me.profile.nik'), value: profile.data.maskedNik, amber: false },
                  { key: 'bank', label: t('me.profile.bank'), value: profile.data.maskedBankAccount, amber: false },
                  profile.data.hasNpwp
                    ? { key: 'npwp', label: t('me.profile.npwp'), value: profile.data.maskedNpwp ?? '', amber: false }
                    : { key: 'npwp', label: t('me.profile.npwp'), value: t('me.profile.npwpNone'), amber: true },
                  { key: 'ptkp', label: t('me.profile.ptkp'), value: profile.data.ptkpStatus, amber: false },
                ].map((row) => (
                  <div
                    key={row.key}
                    className="flex min-h-[52px] items-center justify-between gap-3 border-b border-line/60 px-4 py-3 last:border-b-0"
                  >
                    <span className="text-[13.5px] font-medium text-ink-3">{row.label}</span>
                    <span
                      className={
                        row.amber
                          ? 'text-[13.5px] font-semibold text-amber-2'
                          : 'font-mono text-[13.5px] font-semibold text-ink'
                      }
                    >
                      {row.value}
                    </span>
                  </div>
                ))}
              </Card>
              <p className="mt-2 pl-1 text-[11.5px] leading-snug text-ink-3">{t('me.home.piiHint')}</p>
            </section>
          </div>
        </>
      )}
    </div>
  )
}
