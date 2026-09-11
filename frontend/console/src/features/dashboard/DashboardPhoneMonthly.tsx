/**
 * DashboardPhoneMonthly — the MONTHLY phone home: the same usePnl / usePnlTrend / useOutletRevenue
 * hooks and query keys as the desktop (shared cache), one inverted hero card for the month's net.
 *
 * Since ADR 0082 this is the FALLBACK, not the home: DashboardPhone.tsx renders it only for an
 * office login WITHOUT POS access (an accountant-only login), because today's figures come from
 * restaurant-service's POS_ROLES reads and would 403 for them. Everyone with POS access gets the
 * today-centred home. Kept byte-for-byte as the pre-ADR-0082 screen so the books persona loses
 * nothing.
 *
 * The hero is an INVERTED card (bg-ink-900 + paper-toned text): in dark mode the ink ramp
 * flips so it renders as a light card on the dark page — deliberate, same contrast intent.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { BookOpen, CalendarCheck, Inbox, Store, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { ErrorDiagnostics } from '@/components/ErrorDiagnostics'
import { OverdueSettlementCard } from '@/features/platform/OverdueSettlementCard'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { canFinance } from '@/lib/rolePreset'
import { useSession } from '@/lib/session'
import { usePageAccess } from '@/lib/pageAccess'
import { useTierAccess } from '@/lib/featureTier'
import { cn } from '@/lib/cn'
import { localeOf } from '@/i18n'
import { formatAmount, formatMoney, formatPercent } from '@/lib/money'
import { currentPeriod, formatPeriod, shiftPeriod } from '@/lib/period'
import { PeriodNav } from '@/features/_shared/financeUi'
import { useOpeningBalance, isOpeningBalanceNotRecorded } from '@/features/openingBalances/api'
import { usePnl, usePnlTrend, useOutletRevenue } from './api'
import { readFigures, monthShort } from './figures'
import { DeltaPill } from './DeltaPill'

/** Match the desktop's trailing window so the trend queries share the desktop's cache keys. */
const TREND_MONTHS = 8

export function DashboardPhoneMonthly() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  // The overdue read is FINANCE_ROLES-gated at the gateway, so a manager would 403 on it.
  const auth = useAuth()
  const canSeeSettlements = canFinance(effectiveRoles(auth.roles, auth.elevatedRoles))
  const pageAccess = usePageAccess()
  const tierAccess = useTierAccess()
  const locale = localeOf(i18n.language)
  const [period, setPeriod] = useState(currentPeriod())

  const query = usePnl({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    baseCurrency: company?.baseCurrency ?? 'USD',
    period,
    presentation: undefined,
    enabled: !!company,
  })
  const trend = usePnlTrend({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    baseCurrency: company?.baseCurrency ?? 'USD',
    period,
    presentation: undefined,
    months: TREND_MONTHS,
    enabled: !!company,
  })
  const outletQuery = useOutletRevenue({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    period,
    enabled: !!company,
  })

  // Opening-balances shortcut signal (same cache entry as the desktop dashboard) — see
  // Dashboard.tsx: a settled not-recorded 404 on fresh books surfaces the shortcut.
  const openingQuery = useOpeningBalance({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    enabled: !!company && tierAccess.allows('accounting'),
  })

  if (!company) return null

  const data = query.data ?? null
  const figures = readFigures(data, false)
  const profit = figures.net >= 0
  const margin = figures.revenue > 0 ? figures.net / figures.revenue : 0
  const marginLabel = figures.revenue > 0 ? formatPercent(margin, locale) : '—'

  const points = trend.map((p) => ({
    period: p.period,
    fig: readFigures(p.data, false),
    loaded: p.data != null,
  }))
  const prev = points.length >= 2 ? points[points.length - 2] : null
  const revDelta =
    prev && prev.fig.revenue > 0 ? (figures.revenue - prev.fig.revenue) / prev.fig.revenue : 0
  const prevMonthLabel = prev ? monthShort(prev.period, locale) : ''
  const trendEmpty =
    !query.isLoading &&
    points.every((p) => p.loaded) &&
    points.every((p) => p.fig.net === 0 && p.fig.revenue === 0 && p.fig.expense === 0)
  const showOpeningShortcut =
    trendEmpty && tierAccess.allows('accounting') && isOpeningBalanceNotRecorded(openingQuery.error)

  const outlets = outletQuery.data?.outlets ?? []
  const outletCurrency = outletQuery.data?.currency ?? company.baseCurrency
  const maxOutlet = outlets.reduce((m, o) => Math.max(m, o.revenueMinor), 0)

  const quickTiles = [
    pageAccess.isAllowed('expenses') && tierAccess.allows('expenses')
      ? { key: 'inbox', to: '/expenses', icon: Inbox, label: t('mobile.more.claimInbox') }
      : null,
    pageAccess.isAllowed('close') && tierAccess.allows('orgStructure')
      ? { key: 'close', to: '/close', icon: CalendarCheck, label: t('mobile.more.closeBook') }
      : null,
  ].filter((x) => x != null)

  return (
    <div className="flex flex-col gap-3.5">
      <OverdueSettlementCard session={company} locale={locale} enabled={canSeeSettlements} />

      {/* Header: company + scope, then the period stepper. */}
      <div>
        <h1 className="font-display text-xl font-bold leading-tight tracking-display text-ink">
          {company.name}
        </h1>
        <p className="mt-0.5 text-xs text-ink-3">{t('dashboard.scopeAllUnits')}</p>
      </div>
      <PeriodNav
        period={period}
        locale={locale}
        onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
        onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
        prevLabel={t('dashboard.prevPeriod')}
        nextLabel={t('dashboard.nextPeriod')}
      />

      {data?.usesIllustrativeRules ? (
        <div>
          <Badge tone="amber">
            <TriangleAlert className="size-3" /> {t('dashboard.illustrative')}
          </Badge>
        </div>
      ) : null}

      {query.isError ? (
        <ErrorDiagnostics
          message={t('dashboard.error')}
          pathPrefix="/api/v1/pnl"
          onRecovered={() => query.refetch()}
        />
      ) : (
        <>
          {/* Hero — monthly net on the inverted card. */}
          <div className="rounded-card bg-ink-900 p-5 shadow-lg">
            <div className="font-mono text-2xs font-semibold uppercase tracking-eyebrow text-paper/55">
              {profit ? t('dashboard.netProfit') : t('dashboard.netLoss')} ·{' '}
              {formatPeriod(period, locale)}
            </div>
            {/* Figures split by role (ADR 0077): the ONE figure a screen is about is set in the
                display face at 800, and mono is kept for anything that lines up in a column —
                the sub-stats below, and every table. */}
            {query.isLoading ? (
              <div className="mt-2 h-9 w-52 max-w-full animate-pulse rounded-lg bg-paper/20" />
            ) : (
              <div className="tnum mt-2 font-display text-3xl font-extrabold leading-none tracking-display text-paper">
                {formatMoney(figures.net, company.baseCurrency, locale)}
              </div>
            )}
            {prev && prev.fig.revenue > 0 ? (
              <div className="mt-2.5 flex flex-wrap items-center gap-2">
                <DeltaPill value={revDelta} locale={locale} />
                <span className="text-xs text-paper/55">
                  {t('dashboard.revenueVsPrev', { month: prevMonthLabel })}
                </span>
              </div>
            ) : null}
            {/* Sub-stats are BARE grouped numbers (formatAmount) — the hero figure above already
                names the currency, and a truncated money string would misreport the amount. */}
            <div className="mt-4 flex border-t border-paper/10 pt-4">
              {[
                { key: 'revenue', label: t('dashboard.revenue'), value: formatAmount(figures.revenue, company.baseCurrency, locale) },
                { key: 'expense', label: t('dashboard.expense'), value: formatAmount(figures.expense, company.baseCurrency, locale) },
                { key: 'margin', label: t('dashboard.margin'), value: marginLabel },
              ].map((s) => (
                <div key={s.key} className="min-w-0 flex-1">
                  <div className="text-2xs text-paper/50">{s.label}</div>
                  <div className="tnum mt-0.5 pr-2 font-mono text-sm font-bold text-paper">
                    {s.value}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Brand-new company — first-sale prompt instead of empty figures (UX audit parity). */}
          {trendEmpty ? (
            <Card className="flex flex-col items-center gap-3 p-6 text-center">
              <span className="grid size-11 place-items-center rounded-full bg-emerald-tint text-emerald-2">
                <Store className="size-5" aria-hidden="true" />
              </span>
              <div>
                <div className="text-sm font-semibold text-ink">{t('dashboard.noSalesYet')}</div>
                <div className="mt-1 text-sm text-ink-3">{t('dashboard.noSalesYetHint')}</div>
              </div>
              <Link
                to="/pos"
                viewTransition
                className="rounded-xl bg-emerald px-4 py-2 text-sm font-bold text-on-emerald transition-colors hover:bg-emerald-2"
              >
                {t('dashboard.openTill')}
              </Link>
              {showOpeningShortcut ? (
                <Link
                  to="/opening-balances"
                  viewTransition
                  className="inline-flex items-center gap-1.5 text-sm font-semibold text-emerald-2 hover:underline"
                >
                  <BookOpen className="size-4" aria-hidden="true" />
                  {t('dashboard.openingShortcut')}
                </Link>
              ) : null}
            </Card>
          ) : null}

          {/* Per-outlet contribution — real POSTED figures only, bar = share of the top outlet. */}
          {outlets.length > 0 ? (
            <Card className="p-[18px]">
              <div className="flex items-baseline justify-between">
                <span className="text-base font-bold text-ink">
                  {t('dashboardPhone.perOutlet')}
                </span>
                <span className="text-xs text-ink-3">{formatPeriod(period, locale)}</span>
              </div>
              <div className="mt-3.5 flex flex-col gap-3">
                {outlets.map((o) => (
                  <div key={o.businessId}>
                    <div className="mb-1.5 flex items-baseline justify-between gap-3">
                      <span className="truncate text-sm font-semibold text-ink-2">
                        {o.outletName}
                      </span>
                      <span className="tnum shrink-0 font-mono text-sm font-semibold text-ink">
                        {formatMoney(o.revenueMinor, outletCurrency, locale)}
                      </span>
                    </div>
                    {/* Ink, not the brand ramp (ADR 0077): this is a magnitude bar in a list, not a
                        chart — the design draws it in the same ink as the figure beside it. */}
                    <div className="h-[7px] overflow-hidden rounded-full bg-hover">
                      <div
                        className="h-full rounded-full bg-emerald"
                        style={{ width: maxOutlet > 0 ? `${(o.revenueMinor / maxOutlet) * 100}%` : '0%' }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            </Card>
          ) : null}

          {/* Quick tiles */}
          {quickTiles.length > 0 ? (
            <div className={cn('grid gap-2.5', quickTiles.length > 1 ? 'grid-cols-2' : 'grid-cols-1')}>
              {quickTiles.map((tile) => {
                const TileIcon = tile.icon
                return (
                  <Link
                    key={tile.key}
                    to={tile.to}
                    viewTransition
                    className="flex min-h-[88px] flex-col justify-between rounded-2xl border border-line bg-surface p-3.5 transition-colors hover:border-emerald-line hover:bg-emerald-tint"
                  >
                    <TileIcon className="size-[21px] text-emerald-2" strokeWidth={1.8} aria-hidden />
                    <span className="text-sm font-bold leading-tight text-ink">{tile.label}</span>
                  </Link>
                )
              })}
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}
