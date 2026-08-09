/**
 * The employee self-service dashboard (/me) — a full-screen surface (outside the back-office
 * Shell) any business role may open. It shows the signed-in employee their OWN profile (NIK/bank
 * masked, rule 6), assignments, and payslips with REAL amounts (the authorised self-read, gated
 * server-side to the caller's own rows). A login not yet linked to an employee sees a friendly
 * not-linked state. All copy via i18n; money via Intl.
 */

import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { LogOut, Settings, TriangleAlert, UserRound } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { EmptyState } from '@/features/_shared/financeUi'
import { useMyClaims } from '@/features/expenses/api'
import { ClaimStatusBadge } from '@/features/expenses/parts'
import { formatDate } from '@/features/expenses/format'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { formatMoney } from '@/lib/money'
import { canFinance, canHr, canOps, canPos as canPosRole, canReports, ROLE_HOME } from '@/lib/rolePreset'
import { localeOf } from '@/i18n'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { isNotLinked, useMyProfile, useMySales } from './api'
import { MeHomePhone } from './MeHomePhone'
import { LeaveBalanceCard, MyTimeoff } from './MyTimeoff'
import { PayslipsSection } from './PayslipsSection'

export function Me() {
  const { t, i18n } = useTranslation()
  const auth = useAuth()
  const isPhone = useIsPhone()
  const locale = localeOf(i18n.language)
  // In oidc mode apiFetch uses the bearer (identity derived server-side); companyId/actor are only
  // the cache key + the dev-header fallback. An employee-only login has no loaded company session.
  const companyId = auth.companyId ?? 'me'
  const actor = auth.actor
  const canPos = canPosRole(auth.roles)
  // ADR 0049 P3b — merged/elevated roles, mirroring App.tsx's per-capability booleans (consistency;
  // /me stays reachable by every login so this is a rare path for a device, but never a WRONG
  // one). `officeRoles`/`officeOk` cover the full preset role-based access model Phase 2 union —
  // any login that reaches SOME back-office surface gets a way back to it, not just owner/manager.
  const officeRoles = effectiveRoles(auth.roles, auth.elevatedRoles)
  const officeOk =
    canOps(officeRoles) || canReports(officeRoles) || canFinance(officeRoles) || canHr(officeRoles)

  const profile = useMyProfile({ companyId, actor, enabled: true })
  const notLinked = isNotLinked(profile.error)

  // Below 640px the surface splits into the phone Beranda + per-section screens
  // (/me/payslips, /me/timeoff) — Native Console Android design. Desktop unchanged.
  if (isPhone) return <MeHomePhone />

  return (
    <div className="min-h-screen bg-paper">
      {/* Topbar */}
      <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-line bg-surface/80 px-5 backdrop-blur lg:px-8">
        <Wordmark />
        <div className="flex-1" />
        {/* An employee+cashier/chef/waitress login can jump to the POS; any office-capable login
            (owner/manager/accountant/hr) back to its own back-office home. */}
        {officeOk ? (
          <Link
            to={ROLE_HOME(officeRoles)}
            viewTransition
            className="rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 hover:text-ink"
          >
            {t('me.toDashboard')}
          </Link>
        ) : canPos ? (
          <Link
            to="/pos"
            viewTransition
            className="rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 hover:text-ink"
          >
            {t('me.toPos')}
          </Link>
        ) : null}
        <Link
          to="/me/account"
          viewTransition
          title={t('me.account.title')}
          className="grid size-10 place-items-center rounded-full text-ink-3 hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-emerald"
        >
          <Settings className="size-[18px]" aria-hidden="true" />
          <span className="sr-only">{t('me.account.title')}</span>
        </Link>
        <LanguageSwitcher />
        {AUTH_MODE === 'oidc' && auth.authenticated ? (
          <button
            type="button"
            onClick={auth.logout}
            title={auth.actor}
            className="flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink"
          >
            <LogOut className="size-4" />
            <span className="hidden sm:inline">{t('nav.logout')}</span>
          </button>
        ) : null}
      </header>

      <main className="mx-auto w-full max-w-[1080px] px-5 py-8 lg:px-8">
        {profile.isLoading ? (
          <div>
            <div className="flex items-center gap-3">
              <Skeleton className="size-12 shrink-0 rounded-2xl" />
              <div>
                <Skeleton className="h-6 w-40" />
                <Skeleton className="mt-1.5 h-4 w-24" />
              </div>
            </div>
            <div className="mt-6 grid grid-cols-1 items-start gap-5 lg:grid-cols-[300px_minmax(0,1fr)]">
              <div className="flex flex-col gap-4">
                <Skeleton className="h-40 rounded-card" />
                <Skeleton className="h-24 rounded-card" />
                <Skeleton className="h-24 rounded-card" />
                <Skeleton className="h-24 rounded-card" />
              </div>
              <div className="flex min-w-0 flex-col gap-6">
                <Skeleton className="h-56 rounded-card" />
                <div className="grid grid-cols-1 items-start gap-6 xl:grid-cols-2">
                  <Skeleton className="h-48 rounded-card" />
                  <Skeleton className="h-48 rounded-card" />
                </div>
              </div>
            </div>
          </div>
        ) : notLinked ? (
          <EmptyState title={t('me.notLinked.title')} hint={t('me.notLinked.hint')} />
        ) : profile.isError || !profile.data ? (
          <Card className="p-8 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" />
            {t('me.error')}
          </Card>
        ) : (
          <div>
            {/* Page header */}
            <div className="flex items-center gap-3">
              <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-emerald-tint text-emerald-2">
                <UserRound className="size-6" aria-hidden="true" />
              </span>
              <div>
                <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-ink">
                  {profile.data.fullName}
                </h1>
                <p className="text-sm text-ink-3">{t('me.subtitle')}</p>
              </div>
            </div>

            {/* Native Console Web re-fit: a 300px rail (identity + balances) beside the content
                column (payslips, requests, claims) instead of one long stack. */}
            <div className="mt-6 grid grid-cols-1 items-start gap-5 lg:grid-cols-[300px_minmax(0,1fr)]">
              {/* Rail */}
              <div className="flex flex-col gap-4">
                <Card className="p-5">
                  <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
                    {t('me.home.personalData')}
                  </h2>
                  <div className="mt-3 flex flex-col gap-2.5">
                    <ProfileRow label={t('me.profile.status')}>
                      <Badge tone={profile.data.status === 'ACTIVE' ? 'emerald' : 'amber'}>
                        {t(
                          profile.data.status === 'ACTIVE'
                            ? 'me.profile.active'
                            : 'me.profile.inactive',
                        )}
                      </Badge>
                    </ProfileRow>
                    <ProfileRow label={t('me.profile.nik')}>
                      <span className="font-mono">{profile.data.maskedNik}</span>
                    </ProfileRow>
                    <ProfileRow label={t('me.profile.bank')}>
                      <span className="font-mono">{profile.data.maskedBankAccount}</span>
                    </ProfileRow>
                    <ProfileRow label={t('me.profile.npwp')}>
                      {profile.data.hasNpwp ? (
                        <span className="font-mono">{profile.data.maskedNpwp}</span>
                      ) : (
                        <span className="text-amber">{t('me.profile.npwpNone')}</span>
                      )}
                    </ProfileRow>
                    <ProfileRow label={t('me.profile.ptkp')}>{profile.data.ptkpStatus}</ProfileRow>
                  </div>
                </Card>

                <Card className="p-5">
                  <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
                    {t('me.assignments.title')}
                  </h2>
                  {profile.data.assignments.length === 0 ? (
                    <p className="mt-2.5 text-sm text-ink-3">{t('me.assignments.empty')}</p>
                  ) : (
                    <div className="mt-2.5 flex flex-wrap gap-2">
                      {profile.data.assignments.map((a) => (
                        <span
                          key={a.id}
                          className="rounded-full bg-surface px-3 py-1.5 text-sm text-ink-2 ring-1 ring-line"
                        >
                          <span className="font-semibold text-ink">{a.role}</span>
                          <span className="text-ink-3"> · {a.effectiveFrom}</span>
                        </span>
                      ))}
                    </div>
                  )}
                </Card>

                {/* My time off balance (ADR 0033, Track P Phase P6) */}
                <LeaveBalanceCard companyId={companyId} actor={actor} />

                {/* Sales + commission */}
                <SalesRailCard companyId={companyId} actor={actor} locale={locale} />
              </div>

              {/* Content column */}
              <div className="flex min-w-0 flex-col gap-6">
                {/* Payslips */}
                <PayslipsSection
                  companyId={companyId}
                  actor={actor}
                  locale={locale}
                  employeeName={profile.data.fullName}
                />

                <div className="grid grid-cols-1 items-start gap-6 xl:grid-cols-2">
                  {/* My time off requests (ADR 0033, Track P Phase P6) — balance lives in the rail */}
                  <MyTimeoff companyId={companyId} actor={actor} hideBalance />

                  {/* My expenses (ADR 0030, Phase E6) */}
                  <ClaimsCard companyId={companyId} actor={actor} locale={locale} />
                </div>
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  )
}

function ProfileRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-xs font-medium text-ink-3">{label}</span>
      <span className="text-right text-[12.5px] font-semibold text-ink">{children}</span>
    </div>
  )
}

/**
 * Sales + commission as a rail card (Native Console Web re-fit). Only renders for logins that
 * actually rang sales this month or carry a commission — a back-office login gets nothing.
 */
function SalesRailCard({
  companyId,
  actor,
  locale,
}: {
  companyId: string
  actor: string
  locale: string
}) {
  const { t } = useTranslation()
  const sales = useMySales({ companyId, actor, enabled: true })

  if (!sales.data) return null
  const { salesMinor, currency, commissionBasisPoints, commissionEstimateMinor } = sales.data
  if (salesMinor === 0 && commissionBasisPoints === null) return null

  return (
    <Card className="p-5">
      <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('me.sales.title')}
      </h2>
      <p className="tnum mt-2 font-mono text-[20px] font-bold text-ink">
        {formatMoney(salesMinor, currency, locale)}
      </p>
      {commissionBasisPoints !== null ? (
        <>
          <div className="mt-3 flex items-baseline justify-between gap-3 border-t border-line pt-3">
            <span className="text-xs text-ink-3">
              {t('me.sales.estimate')} · {(commissionBasisPoints / 100).toLocaleString(locale)}%
            </span>
            <span className="tnum font-mono text-[13px] font-bold text-profit-ink">
              {formatMoney(commissionEstimateMinor ?? 0, currency, locale)}
            </span>
          </div>
          <p className="mt-1.5 text-[11px] leading-normal text-ink-3">
            {t('me.sales.estimateHint')}
          </p>
        </>
      ) : null}
    </Card>
  )
}

/**
 * "My expenses" as a claims-list card (ADR 0030, Phase E6; Native Console Web re-fit) — the
 * caller's most recent claims from the first page of {@link useMyClaims}, with the link through
 * to the full `/me/expenses` surface.
 */
function ClaimsCard({
  companyId,
  actor,
  locale,
}: {
  companyId: string
  actor: string
  locale: string
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const claims = useMyClaims({ companyId, actor, page: 0, enabled: true })
  const rows = (claims.data?.content ?? []).slice(0, 4)

  return (
    <section className="min-w-0">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
          {t('me.expenses.card.title')}
        </h2>
        <Button type="button" variant="outline" onClick={() => navigate('/me/expenses')}>
          {t('me.expenses.card.cta')}
        </Button>
      </div>
      {claims.isLoading ? (
        <ListSkeleton rows={4} className="mt-2" />
      ) : rows.length === 0 ? (
        <p className="mt-2 text-sm text-ink-3">{t('me.expenses.card.empty')}</p>
      ) : (
        <Card className="mt-2 rounded-[20px] p-2.5">
          {rows.map((c) => (
            <div key={c.id} className="flex flex-wrap items-center gap-3 rounded-xl px-3 py-2.5">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold text-ink">{c.categoryName}</span>
                  <ClaimStatusBadge status={c.status} />
                </div>
                <p className="mt-0.5 text-xs text-ink-3">
                  {formatDate(c.expenseDate, locale)}
                  {c.merchant ? ` · ${c.merchant}` : null}
                </p>
              </div>
              <span className="tnum font-mono text-[13px] font-semibold text-ink">
                {formatMoney(c.amountMinor, c.currency, locale)}
              </span>
            </div>
          ))}
        </Card>
      )}
    </section>
  )
}
