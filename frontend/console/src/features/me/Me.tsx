/**
 * The employee self-service dashboard (/me) — a full-screen surface (outside the back-office
 * Shell) any business role may open. It shows the signed-in employee their OWN profile (NIK/bank
 * masked, rule 6), assignments, and payslips with REAL amounts (the authorised self-read, gated
 * server-side to the caller's own rows). A login not yet linked to an employee sees a friendly
 * not-linked state. All copy via i18n; money via Intl.
 */

import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { LogOut, Receipt, TriangleAlert, UserRound } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { EmptyState, KpiTile } from '@/features/_shared/financeUi'
import { useMyClaims } from '@/features/expenses/api'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { AUTH_MODE } from '@/lib/config'
import { localeOf } from '@/i18n'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { isNotLinked, useMyProfile, useMySales } from './api'
import { MeHomePhone } from './MeHomePhone'
import { MyTimeoff } from './MyTimeoff'
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
  const canPos = hasAnyRole(auth.roles, 'owner', 'manager', 'cashier')
  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')

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
        {/* An employee+cashier login can jump to the POS; owner/manager back to the dashboard. */}
        {canDashboard ? (
          <Link
            to="/"
            className="rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 hover:text-ink"
          >
            {t('me.toDashboard')}
          </Link>
        ) : canPos ? (
          <Link
            to="/pos"
            className="rounded-xl px-2.5 py-1.5 text-sm font-medium text-ink-3 hover:text-ink"
          >
            {t('me.toPos')}
          </Link>
        ) : null}
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

      <main className="mx-auto w-full max-w-[900px] px-5 py-8 lg:px-8">
        {profile.isLoading ? (
          <Card className="p-10 text-center">
            <Spinner className="mx-auto text-brand-500" />
          </Card>
        ) : notLinked ? (
          <EmptyState title={t('me.notLinked.title')} hint={t('me.notLinked.hint')} />
        ) : profile.isError || !profile.data ? (
          <Card className="p-8 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" />
            {t('me.error')}
          </Card>
        ) : (
          <div className="flex flex-col gap-6">
            {/* Profile */}
            <div>
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

              <Card className="mt-4 grid gap-x-8 gap-y-3 p-5 sm:grid-cols-2">
                <Detail label={t('me.profile.status')}>
                  <Badge tone={profile.data.status === 'ACTIVE' ? 'emerald' : 'amber'}>
                    {t(
                      profile.data.status === 'ACTIVE'
                        ? 'me.profile.active'
                        : 'me.profile.inactive',
                    )}
                  </Badge>
                </Detail>
                <Detail label={t('me.profile.ptkp')}>{profile.data.ptkpStatus}</Detail>
                <Detail label={t('me.profile.nik')}>
                  <span className="font-mono">{profile.data.maskedNik}</span>
                </Detail>
                <Detail label={t('me.profile.bank')}>
                  <span className="font-mono">{profile.data.maskedBankAccount}</span>
                </Detail>
                <Detail label={t('me.profile.npwp')}>
                  {profile.data.hasNpwp ? (
                    <span className="font-mono">{profile.data.maskedNpwp}</span>
                  ) : (
                    <span className="text-amber">{t('me.profile.npwpNone')}</span>
                  )}
                </Detail>
              </Card>
            </div>

            {/* Assignments */}
            <section>
              <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
                {t('me.assignments.title')}
              </h2>
              {profile.data.assignments.length === 0 ? (
                <p className="mt-2 text-sm text-ink-3">{t('me.assignments.empty')}</p>
              ) : (
                <div className="mt-2 flex flex-wrap gap-2">
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
            </section>

            {/* Sales + commission */}
            <SalesSection companyId={companyId} actor={actor} locale={locale} />

            {/* My expenses (ADR 0030, Phase E6) */}
            <ExpensesCard companyId={companyId} actor={actor} />

            {/* My time off (ADR 0033, Track P Phase P6) */}
            <MyTimeoff companyId={companyId} actor={actor} />

            {/* Payslips */}
            <PayslipsSection
              companyId={companyId}
              actor={actor}
              locale={locale}
              employeeName={profile.data.fullName}
            />
          </div>
        )}
      </main>
    </div>
  )
}

function Detail({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 sm:block">
      <span className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">{label}</span>
      <span className="text-sm font-medium text-ink sm:mt-0.5 sm:block">{children}</span>
    </div>
  )
}

function SalesSection({
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

  // Only render for logins that actually rang sales this month or carry a commission — a
  // back-office login (no sales, no commission) gets nothing.
  if (!sales.data) return null
  const { salesMinor, currency, commissionBasisPoints, commissionEstimateMinor } = sales.data
  if (salesMinor === 0 && commissionBasisPoints === null) return null

  return (
    <section>
      <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('me.sales.title')}
      </h2>
      <div className="mt-2 grid gap-3 sm:grid-cols-3">
        <KpiTile
          label={t('me.sales.mySales')}
          minor={salesMinor}
          currency={currency}
          locale={locale}
          loading={false}
        />
        {commissionBasisPoints !== null ? (
          <>
            <Card className="flex flex-col justify-center p-5">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
                {t('me.sales.rate')}
              </span>
              <span className="tnum mt-1 font-mono text-2xl font-bold text-ink">
                {(commissionBasisPoints / 100).toLocaleString(locale)}%
              </span>
            </Card>
            <KpiTile
              label={t('me.sales.estimate')}
              minor={commissionEstimateMinor ?? 0}
              currency={currency}
              locale={locale}
              loading={false}
              emphatic
            />
          </>
        ) : null}
      </div>
      {commissionBasisPoints !== null ? (
        <p className="mt-1.5 text-xs text-ink-3">{t('me.sales.estimateHint')}</p>
      ) : null}
    </section>
  )
}

/**
 * "My expenses" summary card (ADR 0030, Phase E6) — a count of the caller's own draft/pending
 * claims from the first page of {@link useMyClaims} (an approximation beyond the first page, which
 * is fine for a preview badge) and a link through to the full `/me/expenses` surface.
 */
function ExpensesCard({ companyId, actor }: { companyId: string; actor: string }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const claims = useMyClaims({ companyId, actor, page: 0, enabled: true })
  const pendingCount = (claims.data?.content ?? []).filter(
    (c) => c.status === 'DRAFT' || c.status === 'SUBMITTED',
  ).length

  return (
    <section>
      <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('me.expenses.card.title')}
      </h2>
      <Card className="mt-2 flex items-center gap-3 p-5">
        <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-emerald-tint text-emerald-2">
          <Receipt className="size-5" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm text-ink-2">
            {claims.isLoading
              ? '…'
              : pendingCount > 0
                ? t('me.expenses.card.pending', { count: pendingCount })
                : t('me.expenses.card.empty')}
          </p>
        </div>
        <Button type="button" variant="outline" onClick={() => navigate('/me/expenses')}>
          {t('me.expenses.card.cta')}
        </Button>
      </Card>
    </section>
  )
}
