import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ArrowRight, Info, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Segmented } from '@/components/ui/Segmented'
import { useSession } from '@/lib/session'
import { cn } from '@/lib/cn'
import { localeOf } from '@/i18n'
import { formatMoney, formatPercent } from '@/lib/money'
import { currentPeriod, formatPeriod, shiftPeriod } from '@/lib/period'
import { EmptyState, PeriodNav } from '@/features/_shared/financeUi'
import { usePnl, usePnlTrend, type PnlResponse } from './api'

/** How many trailing months the sparkline + revenue-delta look back over. */
const TREND_MONTHS = 8

export function Dashboard() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)

  const [period, setPeriod] = useState(currentPeriod())
  const [view, setView] = useState<'overview' | 'analytics'>('overview')
  const [lens, setLens] = useState('native')

  const otherCurrency = company?.baseCurrency === 'IDR' ? 'USD' : 'IDR'
  const presentation = lens === 'native' ? undefined : lens

  const query = usePnl({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    baseCurrency: company?.baseCurrency ?? 'USD',
    period,
    presentation,
    enabled: !!company,
  })

  const trend = usePnlTrend({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    baseCurrency: company?.baseCurrency ?? 'USD',
    period,
    presentation,
    months: TREND_MONTHS,
    enabled: !!company,
  })

  if (!company) {
    return <EmptyState title={t('dashboard.noCompany')} hint={t('dashboard.noCompanyHint')} />
  }

  const data = query.data ?? null
  const converted = presentation != null && data?.presentationCurrency != null
  const displayCurrency = converted ? data!.presentationCurrency! : company.baseCurrency

  const figures = readFigures(data, converted)
  const profit = figures.net >= 0
  const margin = figures.revenue > 0 ? figures.net / figures.revenue : 0
  const expenseRatio = figures.revenue > 0 ? figures.expense / figures.revenue : 0
  const marginLabel = figures.revenue > 0 ? formatPercent(margin, locale) : '—'

  // Trailing-window series (each point converted on its own merits, matching the active lens).
  const points = trend.map((p) => {
    const c = presentation != null && p.data?.presentationCurrency != null
    return { period: p.period, fig: readFigures(p.data, c), loaded: p.data != null }
  })
  const netSeries = points.map((p) => p.fig.net)
  const spark = buildSparkPath(netSeries, 560, 200, 18)

  const prev = points.length >= 2 ? points[points.length - 2] : null
  const revDelta = prev && prev.fig.revenue > 0 ? (figures.revenue - prev.fig.revenue) / prev.fig.revenue : 0
  const prevMonthLabel = prev ? monthShort(prev.period, locale) : ''

  // At-a-glance derived stats — all real, off the trailing window.
  const netTrend = prev && prev.fig.net !== 0 ? (figures.net - prev.fig.net) / Math.abs(prev.fig.net) : 0
  const best = points.reduce<{ period: string; net: number } | null>((acc, p) => {
    if (!p.loaded) return acc
    return acc == null || p.fig.net > acc.net ? { period: p.period, net: p.fig.net } : acc
  }, null)

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('dashboard.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('dashboard.subtitle')}</p>
        </div>
        <PeriodNav
          period={period}
          locale={locale}
          onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
          onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
          prevLabel={t('dashboard.prevPeriod')}
          nextLabel={t('dashboard.nextPeriod')}
        />
      </div>

      {/* Controls: illustrative badges · view toggle · currency lens */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          {data?.usesIllustrativeRules ? (
            <Badge tone="amber">
              <TriangleAlert className="size-3" /> {t('dashboard.illustrative')}
            </Badge>
          ) : null}
          {converted && data?.usesStubFx ? (
            <Badge tone="amber">
              <TriangleAlert className="size-3" /> {t('dashboard.stubFx')}
              {data.fxAsOf ? (
                <span className="font-normal opacity-80">
                  · {t('dashboard.asOf', { date: data.fxAsOf })}
                </span>
              ) : null}
            </Badge>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Segmented
            ariaLabel={t('dashboard.title')}
            value={view}
            onChange={setView}
            options={[
              { value: 'overview', label: t('dashboard.overview') },
              { value: 'analytics', label: t('dashboard.analytics') },
            ]}
          />
          <label className="flex items-center gap-2.5">
            <span className="text-xs text-ink-3">{t('dashboard.viewIn')}</span>
            <Segmented
              ariaLabel={t('dashboard.viewIn')}
              value={lens}
              onChange={setLens}
              options={[
                { value: 'native', label: company.baseCurrency },
                { value: otherCurrency, label: otherCurrency },
              ]}
            />
          </label>
        </div>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">{t('dashboard.error')}</Card>
      ) : view === 'overview' ? (
        <div className="flex flex-col gap-[18px]">
          {/* Hero: net + sparkline */}
          <Card className="grid items-center gap-8 rounded-[28px] p-7 lg:grid-cols-2 lg:p-9">
            <div>
              <div className="inline-flex items-center gap-2 text-xs font-medium text-ink-3">
                <span className="size-[7px] rounded-full bg-profit [animation:pulse-dot_2s_infinite]" />
                {profit ? t('dashboard.netProfit') : t('dashboard.netLoss')} ·{' '}
                {formatPeriod(period, locale)}
              </div>

              {query.isLoading ? (
                <div className="my-4 h-[54px] w-64 max-w-full animate-pulse rounded-lg bg-ink-100" />
              ) : (
                <div
                  className={cn(
                    'tnum my-4 font-mono text-[clamp(2.2rem,5.4vw,3rem)] font-bold leading-none tracking-[-0.03em]',
                    profit ? 'text-profit-ink' : 'text-loss',
                  )}
                >
                  {formatMoney(figures.net, displayCurrency, locale)}
                </div>
              )}

              {prev ? (
                <div className="flex flex-wrap items-center gap-3">
                  <DeltaPill value={revDelta} locale={locale} />
                  <span className="text-[13px] text-ink-3">
                    {t('dashboard.revenueVsPrev', { month: prevMonthLabel })}
                  </span>
                </div>
              ) : null}

              <div className="mt-6 flex flex-wrap gap-7">
                <Stat
                  label={t('dashboard.revenue')}
                  value={formatMoney(figures.revenue, displayCurrency, locale)}
                  loading={query.isLoading}
                />
                <Stat
                  label={t('dashboard.expense')}
                  value={formatMoney(figures.expense, displayCurrency, locale)}
                  loading={query.isLoading}
                />
                <Stat
                  label={t('dashboard.margin')}
                  value={marginLabel}
                  valueClass="text-profit-ink"
                  loading={query.isLoading}
                />
              </div>
            </div>

            <div>
              <Sparkline spark={spark} />
              <div className="mt-2 flex justify-between px-1">
                {points.map((p) => (
                  <span key={p.period} className="font-mono text-[10px] text-ink-3">
                    {monthShort(p.period, locale)}
                  </span>
                ))}
              </div>
              {converted ? (
                <div className="mt-3 inline-flex items-center gap-1.5 text-xs text-ink-3">
                  <Info className="size-3.5" /> {t('dashboard.presentationNote')}
                </div>
              ) : null}
            </div>
          </Card>

          {/* Outlet contribution + at a glance / ready-to-close */}
          <div className="grid gap-[18px] lg:grid-cols-[1.5fr_1fr]">
            {/* Outlet contribution — only real POSTED figures may appear here (audit finding 15).
                There is no per-outlet ledger feed yet, so the panel shows an honest empty state
                instead of inventing a breakdown. */}
            <Card className="p-6">
              <div className="mb-4 flex items-center justify-between gap-3">
                <h2 className="font-display text-lg font-semibold text-ink">
                  {t('dashboard.outletContribution')}
                </h2>
                <span className="text-[13px] text-ink-3">{t('dashboard.postedToLedger')}</span>
              </div>
              <div className="grid min-h-[220px] place-items-center rounded-xl border border-dashed border-line-strong px-6 py-10 text-center">
                <div>
                  <div className="text-sm font-semibold text-ink-2">
                    {t('dashboard.noOutletData')}
                  </div>
                  <p className="mx-auto mt-1.5 max-w-[36ch] text-[13px] leading-relaxed text-ink-3">
                    {t('dashboard.noOutletDataHint')}
                  </p>
                </div>
              </div>
            </Card>

            <div className="flex flex-col gap-[18px]">
              {/* At a glance — real, derived from the trailing window */}
              <Card className="p-6">
                <h3 className="mb-4 font-display text-base font-semibold text-ink">
                  {t('dashboard.atGlance')}
                </h3>
                <div className="grid grid-cols-2 gap-x-3.5 gap-y-4">
                  <Glance
                    label={t('dashboard.margin')}
                    value={marginLabel}
                    sub={profit ? t('dashboard.healthy') : t('dashboard.negative')}
                    valueClass="text-profit-ink"
                  />
                  <Glance
                    label={t('dashboard.netTrend')}
                    value={prev ? signedPercent(netTrend, locale) : '—'}
                    sub={t('dashboard.months', { count: TREND_MONTHS })}
                    valueClass={netTrend >= 0 ? 'text-profit-ink' : 'text-loss'}
                  />
                  <Glance
                    label={t('dashboard.bestMonth')}
                    value={best ? monthShort(best.period, locale) : '—'}
                    sub={t('dashboard.peak')}
                  />
                  <Glance
                    label={t('dashboard.netProfit')}
                    value={profit ? t('dashboard.positive') : t('dashboard.negative')}
                    sub={formatPeriod(period, locale)}
                    valueClass={profit ? 'text-profit-ink' : 'text-loss'}
                  />
                </div>
              </Card>

              {/* Ready to close — flat cyan tint, one leading button (no gradient, no pill). */}
              <Card className="border-emerald-line bg-emerald-tint p-6 shadow-none">
                <div className="text-[11px] font-bold uppercase tracking-[0.08em] text-emerald-2">
                  {t('dashboard.readyToClose')}
                </div>
                <div className="mt-2 font-display text-lg font-bold text-ink">
                  {formatPeriod(period, locale)}
                </div>
                <p className="mt-1.5 text-[13px] leading-relaxed text-ink-2">
                  {t('dashboard.readyToCloseBody')}
                </p>
                <Link
                  to="/close"
                  className="mt-[18px] flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-emerald text-sm font-bold text-on-emerald transition-colors hover:bg-emerald-2"
                >
                  {t('dashboard.reviewClose')}
                  <ArrowRight className="size-4" />
                </Link>
              </Card>
            </div>
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-[18px]">
          {/* KPI tiles — all real */}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Kpi
              label={t('dashboard.revenue')}
              value={formatMoney(figures.revenue, displayCurrency, locale)}
              note={prev ? `${signedPercent(revDelta, locale)} ${t('dashboard.vsPrev', { month: prevMonthLabel })}` : undefined}
              noteClass={revDelta >= 0 ? 'text-profit-ink' : 'text-loss'}
              loading={query.isLoading}
            />
            <Kpi
              label={t('dashboard.expense')}
              value={formatMoney(figures.expense, displayCurrency, locale)}
              note={t('dashboard.ofRevenue', { pct: formatPercent(expenseRatio, locale) })}
              loading={query.isLoading}
            />
            <Kpi
              label={t('dashboard.netProfit')}
              value={formatMoney(figures.net, displayCurrency, locale)}
              valueClass={profit ? 'text-profit-ink' : 'text-loss'}
              note={profit ? t('dashboard.positive') : t('dashboard.negative')}
              noteClass={profit ? 'text-profit-ink' : 'text-loss'}
              emphatic
              loading={query.isLoading}
            />
            <Kpi
              label={t('dashboard.margin')}
              value={marginLabel}
              valueClass="text-profit-ink"
              note={profit ? t('dashboard.healthy') : t('dashboard.negative')}
              loading={query.isLoading}
            />
          </div>

          <div className="grid gap-[18px] lg:grid-cols-[1.4fr_1fr]">
            {/* Per-outlet revenue vs expense — real posted figures only (audit finding 15). */}
            <Card className="p-6">
              <div className="mb-4 flex items-center justify-between gap-3">
                <h2 className="font-display text-lg font-semibold text-ink">
                  {t('dashboard.revenueVsExpense')}
                </h2>
                <span className="text-[13px] text-ink-3">{t('dashboard.postedToLedger')}</span>
              </div>
              <div className="grid min-h-[230px] place-items-center rounded-xl border border-dashed border-line-strong px-6 py-10 text-center">
                <div>
                  <div className="text-sm font-semibold text-ink-2">
                    {t('dashboard.noOutletData')}
                  </div>
                  <p className="mx-auto mt-1.5 max-w-[36ch] text-[13px] leading-relaxed text-ink-3">
                    {t('dashboard.noOutletDataHint')}
                  </p>
                </div>
              </div>
            </Card>

            {/* Revenue allocation — real expense vs net composition */}
            <Card className="p-6">
              <h2 className="font-display text-lg font-semibold text-ink">
                {t('dashboard.revenueAllocation')}
              </h2>
              <p className="mb-4 mt-0.5 text-xs text-ink-3">{t('dashboard.shareOfRevenue')}</p>
              <AllocationBar
                segments={[
                  { label: t('dashboard.expense'), share: expenseRatio, color: 'var(--color-loss)' },
                  {
                    label: t('dashboard.netProfit'),
                    share: Math.max(0, 1 - expenseRatio),
                    color: 'var(--color-profit)',
                  },
                ]}
                locale={locale}
              />
            </Card>
          </div>
        </div>
      )}
    </div>
  )
}

function Stat({
  label,
  value,
  valueClass,
  loading,
}: {
  label: string
  value: string
  valueClass?: string
  loading: boolean
}) {
  return (
    <div>
      <div className="text-xs text-ink-3">{label}</div>
      {loading ? (
        <div className="mt-2 h-5 w-20 animate-pulse rounded bg-ink-100" />
      ) : (
        <div className={cn('tnum mt-1 font-mono text-[19px] font-semibold', valueClass ?? 'text-ink')}>
          {value}
        </div>
      )}
    </div>
  )
}

function Kpi({
  label,
  value,
  valueClass,
  note,
  noteClass,
  emphatic,
  loading,
}: {
  label: string
  value: string
  valueClass?: string
  note?: string
  noteClass?: string
  emphatic?: boolean
  loading: boolean
}) {
  return (
    <Card className={cn('p-5', emphatic && 'outline outline-2 -outline-offset-2 outline-brand-200')}>
      <div className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">{label}</div>
      {loading ? (
        <div className="mt-2 h-7 w-28 animate-pulse rounded bg-ink-100" />
      ) : (
        <div className={cn('tnum mt-2 font-mono text-[25px] font-semibold', valueClass ?? 'text-ink')}>
          {value}
        </div>
      )}
      {note ? <div className={cn('mt-1.5 text-xs font-semibold', noteClass ?? 'text-ink-3')}>{note}</div> : null}
    </Card>
  )
}

function Glance({
  label,
  value,
  sub,
  valueClass,
}: {
  label: string
  value: string
  sub: string
  valueClass?: string
}) {
  return (
    <div>
      <div className="text-xs text-ink-3">{label}</div>
      <div className={cn('tnum mt-1 font-mono text-[22px] font-bold', valueClass ?? 'text-ink')}>
        {value}
      </div>
      <div className="text-[11px] text-ink-3">{sub}</div>
    </div>
  )
}

function DeltaPill({ value, locale }: { value: number; locale: string }) {
  const up = value >= 0
  return (
    <span
      className={cn(
        'tnum inline-flex items-center gap-1 rounded-full px-3 py-1.5 font-mono text-[13px] font-bold',
        up ? 'bg-tint-profit text-profit-ink' : 'bg-tint-loss text-loss',
      )}
    >
      {up ? '▲' : '▼'} {formatPercent(Math.abs(value), locale)}
    </span>
  )
}

function AllocationBar({
  segments,
  locale,
}: {
  segments: { label: string; share: number; color: string }[]
  locale: string
}) {
  return (
    <div className="space-y-4">
      <div className="flex h-3 overflow-hidden rounded-full">
        {segments.map((s) => (
          <div key={s.label} style={{ width: `${s.share * 100}%`, backgroundColor: s.color }} />
        ))}
      </div>
      <div className="space-y-0.5">
        {segments.map((s) => (
          <div key={s.label} className="flex items-center justify-between py-1.5">
            <span className="inline-flex items-center gap-2 text-sm text-ink-2">
              <span className="size-2.5 rounded-[3px]" style={{ backgroundColor: s.color }} aria-hidden />
              {s.label}
            </span>
            <span className="tnum font-mono text-sm font-semibold text-ink-3">
              {formatPercent(s.share, locale)}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

function Sparkline({ spark }: { spark: SparkPath }) {
  return (
    <svg viewBox="0 0 560 200" preserveAspectRatio="none" className="block h-[200px] w-full">
      {/* Charts are brand-neutral cyan (design 2a) — the net FIGURE is green, the chart is not.
          Tokens, not literals, so dark mode follows (audit finding 03). */}
      <defs>
        <linearGradient id="spark-fill" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor="var(--color-brand-500)" stopOpacity="0.2" />
          <stop offset="1" stopColor="var(--color-brand-500)" stopOpacity="0" />
        </linearGradient>
      </defs>
      {spark.area ? <path d={spark.area} fill="url(#spark-fill)" /> : null}
      {spark.line ? (
        <path
          d={spark.line}
          fill="none"
          stroke="var(--color-brand-500)"
          strokeWidth="2.8"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ) : null}
      <circle
        cx={spark.lx}
        cy={spark.ly}
        r="5.5"
        fill="var(--color-surface)"
        stroke="var(--color-brand-500)"
        strokeWidth="2.5"
      />
    </svg>
  )
}

interface Figures {
  revenue: number
  expense: number
  net: number
}

function readFigures(data: PnlResponse | null, converted: boolean): Figures {
  if (!data) return { revenue: 0, expense: 0, net: 0 }
  if (converted) {
    return {
      revenue: data.presentationRevenueMinor ?? 0,
      expense: data.presentationExpenseMinor ?? 0,
      net: data.presentationNetMinor ?? 0,
    }
  }
  return { revenue: data.revenueMinor, expense: data.expenseMinor, net: data.netMinor }
}

function signedPercent(value: number, locale: string): string {
  const arrow = value >= 0 ? '▲' : '▼'
  return `${arrow} ${formatPercent(Math.abs(value), locale)}`
}

/** 'YYYY-MM' → the locale-short month name (e.g. "Jun"). */
function monthShort(period: string, locale: string): string {
  const [y, m] = period.split('-').map(Number)
  return new Date(y, m - 1, 1).toLocaleDateString(locale, { month: 'short' })
}

interface SparkPath {
  line: string
  area: string
  lx: number
  ly: number
}

/** Smooth (cubic) area+line path through `values`, normalized to the [pad, w/h-pad] box. */
function buildSparkPath(values: number[], w: number, h: number, pad: number): SparkPath {
  const n = values.length
  if (n < 2) return { line: '', area: '', lx: w - pad, ly: h / 2 }
  const max = Math.max(...values)
  const min = Math.min(...values)
  const span = max - min || 1
  const x = (i: number) => pad + (w - 2 * pad) * (i / (n - 1))
  const y = (v: number) => pad + (h - 2 * pad) * (1 - (v - min) / span)
  let line = `M${x(0).toFixed(1)},${y(values[0]).toFixed(1)}`
  for (let i = 1; i < n; i++) {
    const cx = (x(i - 1) + x(i)) / 2
    line +=
      ` C${cx.toFixed(1)},${y(values[i - 1]).toFixed(1)}` +
      ` ${cx.toFixed(1)},${y(values[i]).toFixed(1)}` +
      ` ${x(i).toFixed(1)},${y(values[i]).toFixed(1)}`
  }
  const area = `${line} L${x(n - 1).toFixed(1)},${(h - pad).toFixed(1)} L${x(0).toFixed(1)},${(h - pad).toFixed(1)} Z`
  return { line, area, lx: x(n - 1), ly: y(values[n - 1]) }
}
