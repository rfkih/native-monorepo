/**
 * DashboardPhone — the manager phone home (Native Console Android design), rendered by
 * Dashboard.tsx below the 640px cutoff. HONEST monthly figures only: the same usePnl /
 * usePnlTrend / useOutletRevenue hooks and query keys as the desktop (shared cache), no
 * invented "today" numbers — the design's daily hero waits for a daily-sales endpoint.
 *
 * The hero is an INVERTED card (bg-ink-900 + paper-toned text): in dark mode the ink ramp
 * flips so it renders as a light card on the dark page — deliberate, same contrast intent.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { CalendarCheck, Inbox, Store, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { ErrorDiagnostics } from '@/components/ErrorDiagnostics'
import { useSession } from '@/lib/session'
import { usePageAccess } from '@/lib/pageAccess'
import { useTierAccess } from '@/lib/featureTier'
import { cn } from '@/lib/cn'
import { localeOf } from '@/i18n'
import { formatAmount, formatMoney, formatPercent } from '@/lib/money'
import { currentPeriod, formatPeriod, shiftPeriod } from '@/lib/period'
import { PeriodNav } from '@/features/_shared/financeUi'
import { usePnl, usePnlTrend, useOutletRevenue } from './api'
import { readFigures, monthShort } from './figures'
import { DeltaPill } from './DeltaPill'

/** Match the desktop's trailing window so the trend queries share the desktop's cache keys. */
const TREND_MONTHS = 8

export function DashboardPhone() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
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
      {/* Header: company + scope, then the period stepper. */}
      <div>
        <h1 className="font-display text-[19px] font-bold leading-tight tracking-[-0.01em] text-ink">
          {company.name}
        </h1>
        <p className="mt-0.5 text-[12.5px] text-ink-3">{t('dashboard.scopeAllUnits')}</p>
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
          <div className="rounded-[22px] bg-ink-900 p-5 shadow-lg">
            <div className="font-mono text-[11px] font-semibold uppercase tracking-[0.07em] text-paper/55">
              {profit ? t('dashboard.netProfit') : t('dashboard.netLoss')} ·{' '}
              {formatPeriod(period, locale)}
            </div>
            {query.isLoading ? (
              <div className="mt-2 h-9 w-52 max-w-full animate-pulse rounded-lg bg-paper/20" />
            ) : (
              <div className="tnum mt-2 font-mono text-[30px] font-bold leading-none tracking-[-0.025em] text-paper">
                {formatMoney(figures.net, company.baseCurrency, locale)}
              </div>
            )}
            {prev && prev.fig.revenue > 0 ? (
              <div className="mt-2.5 flex flex-wrap items-center gap-2">
                <DeltaPill value={revDelta} locale={locale} />
                <span className="text-[12px] text-paper/55">
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
                  <div className="text-[11px] text-paper/50">{s.label}</div>
                  <div className="tnum mt-0.5 pr-2 font-mono text-[13.5px] font-bold text-paper">
                    {s.value}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Brand-new company — first-sale prompt instead of empty figures (UX audit parity). */}
          {trendEmpty ? (
            <Card className="flex flex-col items-center gap-3 p-6 text-center">
              <span className="grid size-11 place-items-center rounded-full bg-brand-50 text-brand-700">
                <Store className="size-5" aria-hidden="true" />
              </span>
              <div>
                <div className="text-sm font-semibold text-ink">{t('dashboard.noSalesYet')}</div>
                <div className="mt-1 text-[13px] text-ink-3">{t('dashboard.noSalesYetHint')}</div>
              </div>
              <Link
                to="/pos"
                className="rounded-xl bg-emerald px-4 py-2 text-sm font-bold text-on-emerald transition-colors hover:bg-emerald-2"
              >
                {t('dashboard.openTill')}
              </Link>
            </Card>
          ) : null}

          {/* Per-outlet contribution — real POSTED figures only, bar = share of the top outlet. */}
          {outlets.length > 0 ? (
            <Card className="p-[18px]">
              <div className="flex items-baseline justify-between">
                <span className="text-[14.5px] font-bold text-ink">
                  {t('dashboardPhone.perOutlet')}
                </span>
                <span className="text-[11.5px] text-ink-3">{formatPeriod(period, locale)}</span>
              </div>
              <div className="mt-3.5 flex flex-col gap-3">
                {outlets.map((o) => (
                  <div key={o.businessId}>
                    <div className="mb-1.5 flex items-baseline justify-between gap-3">
                      <span className="truncate text-[13px] font-semibold text-ink-2">
                        {o.outletName}
                      </span>
                      <span className="tnum shrink-0 font-mono text-[13px] font-semibold text-ink">
                        {formatMoney(o.revenueMinor, outletCurrency, locale)}
                      </span>
                    </div>
                    <div className="h-[7px] overflow-hidden rounded-full bg-ink-50">
                      <div
                        className="h-full rounded-full bg-brand-500"
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
                    className="flex min-h-[88px] flex-col justify-between rounded-[18px] border border-line bg-surface p-3.5 transition-colors hover:border-emerald-line hover:bg-emerald-tint"
                  >
                    <TileIcon className="size-[21px] text-emerald-2" strokeWidth={1.8} aria-hidden />
                    <span className="text-[13.5px] font-bold leading-tight text-ink">{tile.label}</span>
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
