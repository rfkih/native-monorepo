/**
 * DashboardPhone — the manager phone home (Native Console Android design, ADR 0082), rendered by
 * Dashboard.tsx below the 640px cutoff. It reads TODAY, not the month: the company's net sales so
 * far against the same weekday last week over a seven-day strip, four figures about the day,
 * what needs a decision, today's split by outlet, the best sellers, and four doors.
 *
 * Every figure is honest and sourced: the days come from restaurant-service's per-outlet
 * `GET /api/v1/sales/daily` (todayApi.ts fans out one call per outlet, lib/todayView.ts folds
 * them); open bills and best sellers reuse the POS hooks' cache entries; the tasks come from the
 * claim inbox, the ADR 0081 catalog rules and the close history. "Omzet" is restaurant's gross
 * sales net of refunds — not a GL word, so ADR 0071 AR-1 does not route it through finance; the
 * gross margin is the sale-time COGS snapshot (V44) and says when it only covers part of the day.
 *
 * Only an office login WITH POS access sees this (owner/manager — the reads are POS_ROLES routes);
 * a books-only login (accountant) keeps the monthly composition in DashboardPhoneMonthly.tsx.
 *
 * Draws no chrome of its own (ADR 0075 N2): the header row is in-flow, the Shell's topbar is the
 * one sticky header. The hero is an INVERTED card (bg-ink-900 + paper-toned text): in dark mode
 * the ink ramp flips so it renders as a light card on the dark page — deliberate.
 */
import { Suspense, lazy, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  BookOpen,
  ChartNoAxesColumn,
  ChevronRight,
  ClipboardCheck,
  CookingPot,
  EllipsisVertical,
  Inbox,
  NotebookText,
  Store,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { ErrorDiagnostics } from '@/components/ErrorDiagnostics'
import { OverdueSettlementCard } from '@/features/platform/OverdueSettlementCard'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { canFinance, canHr, canPos } from '@/lib/rolePreset'
import { useSession, type CompanySession } from '@/lib/session'
import { usePageAccess } from '@/lib/pageAccess'
import { useTierAccess } from '@/lib/featureTier'
import { cn } from '@/lib/cn'
import { localeOf } from '@/i18n'
import { formatMoney, formatPercent, formatSignedMoney } from '@/lib/money'
import { currentPeriod, formatPeriod, shiftPeriod } from '@/lib/period'
import { useOutlets } from '@/features/org/api'
import { useClaims } from '@/features/expenses/api'
import { useCloseHistory } from '@/features/close/api'
import { useOpeningBalance, isOpeningBalanceNotRecorded } from '@/features/openingBalances/api'
import {
  useIngredients,
  useIngredientStockSummary,
  usageDayKey,
  usageWindowKeys,
} from '@/features/inventory/ingredientApi'
import { USAGE_WINDOW_DAYS, buildRows, usageRates } from '@/features/inventory/lib/catalogView'
import { usePnl } from './api'
import { readFigures } from './figures'
import { DashboardPhoneMonthly } from './DashboardPhoneMonthly'
import { useDailySalesByOutlet, useItemSalesByOutlet, useOpenBillsByOutlet } from './todayApi'
import {
  WEEK_DAYS,
  avgBill,
  dayKeyOffset,
  figuresFor,
  grossMargin,
  initials,
  jakartaDayBounds,
  lowStockNames,
  mergeDaily,
  mergeTopItems,
  outletShares,
  periodToClose,
  weekBars,
  weekWindow,
  weekdayDelta,
} from './lib/todayView'

/** Lazy — keeps the stocktake code out of the main chunk until the tile is used (as MorePage). */
const StandaloneStocktake = lazy(() =>
  import('@/features/stocktake/StandaloneStocktake').then((m) => ({
    default: m.StandaloneStocktake,
  })),
)

/** The outlet-local zone every "today" here is read in — the server's OutletZone. */
const OUTLET_ZONE = 'Asia/Jakarta'

const SECTION_LABEL = 'pl-1 text-[12px] font-semibold text-ink-3'
const LIST_CARD = 'mt-2 overflow-hidden rounded-[18px] border border-line bg-surface'
const TILE_CLASS =
  'flex min-h-[60px] items-center gap-[11px] rounded-2xl border border-line bg-surface px-3.5 py-3 text-left text-[13.5px] font-semibold leading-tight text-ink transition-[background-color,border-color,transform] duration-150 hover:border-line-strong hover:bg-hover active:scale-[0.98] motion-reduce:active:scale-100'

export function DashboardPhone() {
  const { company } = useSession()
  const auth = useAuth()
  if (!company) return null
  // The today reads are POS_ROLES routes; a login without POS access (books-only) would 403 on
  // every one of them, so it keeps the monthly home. Raw roles, as MorePage's posOk — POS access
  // is the outlet token's own, never elevated.
  return canPos(auth.roles) ? <TodayHome company={company} /> : <DashboardPhoneMonthly />
}

function TodayHome({ company }: { company: CompanySession }) {
  const { t, i18n } = useTranslation()
  const auth = useAuth()
  const roles = effectiveRoles(auth.roles, auth.elevatedRoles)
  const financeOk = canFinance(roles)
  const hrOk = canHr(roles)
  const posOk = canPos(auth.roles)
  const pageAccess = usePageAccess()
  const tierAccess = useTierAccess()
  const locale = localeOf(i18n.language)
  const [stocktakeOpen, setStocktakeOpen] = useState(false)

  // Today in the outlet's zone; the fetch window reaches back one extra day so the same weekday
  // last week is in it (the strip shows seven, the comparison needs the eighth).
  const todayKey = usageDayKey()
  const weekKeys = weekWindow(todayKey)
  const window = { from: dayKeyOffset(todayKey, -WEEK_DAYS), to: todayKey }

  const outletsQuery = useOutlets(company.companyId, company.actor)
  const outlets = outletsQuery.data ?? []
  const outletIds = outlets.map((o) => o.id)
  const daily = useDailySalesByOutlet(company, outletIds, window)
  const bills = useOpenBillsByOutlet(company, outletIds)
  const items = useItemSalesByOutlet(company, outletIds, jakartaDayBounds(todayKey))

  // Tasks — each gated exactly as the More page gates its door to the same place.
  const claimsOk = hrOk && pageAccess.isAllowed('expenses') && tierAccess.allows('expenses')
  const stockOk = posOk && pageAccess.isAllowed('menu') && tierAccess.allows('products')
  const closeOk = financeOk && pageAccess.isAllowed('close') && tierAccess.allows('orgStructure')
  const claimsQuery = useClaims({
    companyId: company.companyId,
    actor: company.actor,
    status: 'SUBMITTED',
    size: 1,
    enabled: claimsOk,
  })
  const ingredientsQuery = useIngredients(company, stockOk)
  const stockSummaryQuery = useIngredientStockSummary(
    company,
    usageWindowKeys(USAGE_WINDOW_DAYS),
    stockOk,
  )
  const closeQuery = useCloseHistory({
    companyId: company.companyId,
    actor: company.actor,
    enabled: closeOk,
  })

  // Fresh books: the first-sale prompt needs to know the ledger is empty too — this month AND
  // last, so an established outlet closed for a week over a month start is not told it has never
  // sold. Two /pnl calls, the same cache entries Laporan's twelve-month trend reads.
  const period = currentPeriod()
  const pnlQuery = usePnl({
    companyId: company.companyId,
    actor: company.actor,
    baseCurrency: company.baseCurrency,
    period,
    presentation: undefined,
    enabled: true,
  })
  const prevPnlQuery = usePnl({
    companyId: company.companyId,
    actor: company.actor,
    baseCurrency: company.baseCurrency,
    period: shiftPeriod(period, -1),
    presentation: undefined,
    enabled: true,
  })

  const series = mergeDaily(daily.byOutlet)
  const today = figuresFor(series, todayKey)
  const bars = weekBars(series, weekKeys)
  const delta = weekdayDelta(series, todayKey)
  const avg = avgBill(today)
  const margin = grossMargin(today)
  const currency = company.baseCurrency
  const outletCount = outletIds.length
  const allFailed = outletCount > 0 && daily.failedCount === outletCount
  const weekAllZero =
    !daily.isLoading &&
    weekKeys.every((k) => figuresFor(series, k).txn === 0 && figuresFor(series, k).net === 0)
  // A failed /pnl is not an empty one: only a SUCCESSFUL read with all-zero figures counts.
  const ledgerEmpty = [pnlQuery, prevPnlQuery].every((q) => {
    if (!q.isSuccess) return false
    const f = readFigures(q.data ?? null, false)
    return f.revenue === 0 && f.expense === 0 && f.net === 0
  })
  const showFirstSale =
    !outletsQuery.isLoading && daily.failedCount === 0 && weekAllZero && ledgerEmpty
  const openingQuery = useOpeningBalance({
    companyId: company.companyId,
    actor: company.actor,
    enabled: showFirstSale && tierAccess.allows('accounting'),
  })
  const showOpeningShortcut =
    showFirstSale &&
    tierAccess.allows('accounting') &&
    isOpeningBalanceNotRecorded(openingQuery.error)

  const openBills = bills.byOutlet.flatMap((list) => list ?? [])
  const openBillsValue = openBills.reduce((s, b) => s + b.runningTotalMinor, 0)
  const topItems = mergeTopItems(items.byOutlet, 3)
  const shares = outletShares(
    outlets.map((o, i) => ({
      id: o.id,
      name: o.name,
      net: figuresFor(mergeDaily([daily.byOutlet[i]]), todayKey).net,
    })),
  )

  const pendingClaims = claimsOk ? (claimsQuery.data?.totalElements ?? 0) : 0
  const lowStock =
    stockOk && ingredientsQuery.data
      ? lowStockNames(
          buildRows(
            ingredientsQuery.data,
            usageRates(stockSummaryQuery.data ?? []).rateById,
            new Map(),
          ),
          3,
        )
      : { count: 0, names: [] }
  const openPeriod =
    closeOk && closeQuery.data
      ? periodToClose(
          closeQuery.data.map((c) => c.period),
          period,
        )
      : null

  const tasks = [
    pendingClaims > 0
      ? {
          key: 'claims',
          to: '/expenses',
          icon: Inbox,
          label: t('mobile.more.claimInbox'),
          sub: t('dashboardPhone.claimsWaiting', { count: pendingClaims }),
          count: pendingClaims,
        }
      : null,
    lowStock.count > 0
      ? {
          key: 'stock',
          to: '/inventory',
          icon: TriangleAlert,
          label: t('dashboardPhone.lowStock'),
          sub: new Intl.ListFormat(locale, { style: 'short', type: 'unit' }).format(lowStock.names),
          count: lowStock.count,
        }
      : null,
    openPeriod
      ? {
          key: 'close',
          to: '/close',
          icon: BookOpen,
          label: t('dashboardPhone.closePeriod', { month: formatPeriod(openPeriod, locale) }),
          sub: t('dashboardPhone.periodStillOpen'),
          count: 1,
        }
      : null,
  ].filter((x) => x != null)

  const doors: {
    key: string
    icon: LucideIcon
    label: string
    to?: string
    onClick?: () => void
  }[] = [
    stockOk
      ? {
          key: 'stocktake',
          icon: ClipboardCheck,
          label: t('mobile.more.stocktake'),
          onClick: () => setStocktakeOpen(true),
        }
      : null,
    stockOk
      ? { key: 'menu', to: '/menu', icon: NotebookText, label: t('mobile.more.menuPrices') }
      : null,
    posOk && pageAccess.isAllowed('kitchen') && tierAccess.allows('kitchen')
      ? { key: 'kitchen', to: '/kitchen', icon: CookingPot, label: t('mobile.more.kitchenDisplay') }
      : null,
    pageAccess.isAllowed('dashboard')
      ? {
          key: 'pnl',
          to: '/statements/income',
          icon: ChartNoAxesColumn,
          label: t('statements.phone.tab.pnl'),
        }
      : null,
  ].filter((x) => x != null)

  const dateLine = t('dashboardPhone.dateLine', {
    date: new Intl.DateTimeFormat(locale, {
      weekday: 'long',
      day: 'numeric',
      month: 'short',
      timeZone: OUTLET_ZONE,
    }).format(new Date()),
    outlets: t('dashboard.activeOutlets', { count: outletCount }),
  })
  const weekdayName = (key: string) =>
    new Intl.DateTimeFormat(locale, { weekday: 'long', timeZone: 'UTC' }).format(
      new Date(`${key}T00:00:00Z`),
    )
  const weekdayShort = (key: string) =>
    new Intl.DateTimeFormat(locale, { weekday: 'short', timeZone: 'UTC' }).format(
      new Date(`${key}T00:00:00Z`),
    )
  const integer = new Intl.NumberFormat(locale)
  const signedInteger = new Intl.NumberFormat(locale, { signDisplay: 'exceptZero' })
  const signedPercent = new Intl.NumberFormat(locale, {
    style: 'percent',
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
    signDisplay: 'exceptZero',
  })
  const lastWeekday = delta ? weekdayName(delta.lastKey) : ''

  const stats = [
    {
      key: 'txn',
      label: t('dashboardPhone.transactions'),
      value: integer.format(today.txn),
      sub: delta
        ? t('dashboardPhone.vsLastWeekday', {
            delta: signedInteger.format(delta.txn),
            day: lastWeekday,
          })
        : '',
    },
    {
      key: 'avg',
      label: t('dashboardPhone.avgBill'),
      value: avg != null ? formatMoney(Math.round(avg), currency, locale) : '—',
      sub:
        delta?.avgPct != null
          ? t('dashboardPhone.vsLastWeekday', {
              delta: signedPercent.format(delta.avgPct),
              day: lastWeekday,
            })
          : '',
    },
    {
      key: 'bills',
      label: t('dashboardPhone.openBills'),
      // A failed bills read is "—", never a confident "none open" on a floor with open tables.
      value: bills.isLoading
        ? null
        : bills.failedCount > 0
          ? '—'
          : integer.format(openBills.length),
      sub:
        bills.isLoading || bills.failedCount > 0
          ? ''
          : openBills.length > 0
            ? t('dashboardPhone.openBillsValue', {
                amount: formatMoney(openBillsValue, currency, locale),
              })
            : t('dashboardPhone.noOpenBills'),
    },
    {
      key: 'margin',
      label: t('dashboardPhone.grossMargin'),
      value: margin ? formatPercent(margin.ratio, locale) : '—',
      sub: margin
        ? margin.partial
          ? t('dashboardPhone.cogsPartial', {
              costed: integer.format(today.costed),
              total: integer.format(today.txn),
            })
          : t('dashboardPhone.cogs', { amount: formatMoney(today.cogs ?? 0, currency, locale) })
        : t('dashboardPhone.noCogs'),
    },
  ]

  return (
    <div className="flex flex-col gap-3">
      <OverdueSettlementCard session={company} locale={locale} enabled={financeOk} />

      {/* Header — in-flow, the Shell's topbar is the one sticky header (ADR 0075 N2). */}
      <div className="flex items-center gap-3">
        <div
          aria-hidden="true"
          className="grid size-11 shrink-0 place-items-center rounded-[15px] bg-ink-900 font-display text-[15px] font-extrabold tracking-[-0.02em] text-paper"
        >
          {initials(company.name)}
        </div>
        <div className="min-w-0 flex-1">
          <h1 className="truncate font-display text-[19px] font-extrabold leading-tight tracking-[-0.025em] text-ink">
            {company.name}
          </h1>
          <p className="mt-0.5 text-[12.5px] font-medium text-ink-3">{dateLine}</p>
        </div>
        <Link
          to="/more"
          viewTransition
          aria-label={t('dashboardPhone.menu')}
          className="-mr-2.5 grid size-11 shrink-0 place-items-center rounded-full text-ink-2 transition-[background-color,transform] duration-150 hover:bg-hover active:scale-[0.94] motion-reduce:active:scale-100"
        >
          <EllipsisVertical className="size-5" aria-hidden="true" />
        </Link>
      </div>

      {today.illustrative ? (
        <div>
          <Badge tone="amber">
            <TriangleAlert className="size-3" /> {t('dashboard.illustrative')}
          </Badge>
        </div>
      ) : null}

      {outletsQuery.isError ? (
        <ErrorDiagnostics
          message={t('dashboardPhone.error')}
          pathPrefix="/api/v1/outlets"
          onRecovered={() => outletsQuery.refetch()}
        />
      ) : allFailed ? (
        <ErrorDiagnostics
          message={t('dashboardPhone.error')}
          pathPrefix="/api/v1/sales/daily"
          onRecovered={daily.retryFailed}
        />
      ) : (
        <>
          {/* Hero — today's net on the inverted card, against the same weekday last week. */}
          <div
            className="rise-in rounded-[22px] bg-ink-900 px-5 pb-[18px] pt-5 shadow-lg"
            style={{ animationDelay: '0.05s' }}
          >
            <div className="flex items-center gap-2.5">
              <span className="flex-1 text-[12px] font-semibold tracking-[0.02em] text-paper/60">
                {t('dashboardPhone.todayRevenue')}
              </span>
              {delta ? (
                <span className="tnum grid h-6 shrink-0 place-items-center rounded-full bg-paper/10 px-2.5 text-[11.5px] font-bold text-paper">
                  {signedPercent.format(delta.netPct)}
                </span>
              ) : null}
            </div>
            {/* The ONE figure this screen is about is set in the display face at 800; mono is kept
                for anything that lines up in a column — the tiles and lists below. */}
            {daily.isLoading || outletsQuery.isLoading ? (
              <div className="mt-2 h-[33px] w-56 max-w-full animate-pulse rounded-lg bg-paper/20" />
            ) : (
              <div
                className="num-rise tnum mt-2 font-display text-[33px] font-extrabold leading-none tracking-[-0.04em] text-paper"
                style={{ animationDelay: '0.18s' }}
              >
                {formatMoney(today.net, currency, locale)}
              </div>
            )}
            {delta ? (
              <div className="mt-2 text-[12.5px] font-medium text-paper/60">
                <span
                  className={cn('tnum font-bold', delta.net >= 0 ? 'text-profit' : 'text-loss')}
                >
                  {formatSignedMoney(delta.net, currency, locale)}
                </span>{' '}
                {t('dashboardPhone.fromLastWeekday', { day: lastWeekday })}
              </div>
            ) : !daily.isLoading && today.net > 0 ? (
              <div className="mt-2 text-[12.5px] font-medium text-paper/60">
                {t('dashboardPhone.noComparison', { day: weekdayName(todayKey) })}
              </div>
            ) : null}
            <div
              role="img"
              aria-label={t('dashboardPhone.weekChart', { count: WEEK_DAYS })}
              className="mt-5 flex h-[94px] items-end gap-[7px]"
            >
              {bars.map((b, i) => (
                <div
                  key={b.key}
                  className="flex h-full flex-1 flex-col items-center justify-end gap-[9px]"
                >
                  <div className="flex min-h-0 w-full flex-1 items-end">
                    <div
                      className={cn(
                        'bar-up w-full rounded-[6px]',
                        b.isToday ? 'bg-paper' : 'bg-paper/25',
                      )}
                      style={{
                        height: `${Math.max(b.pct, 2.5)}%`,
                        animationDelay: `${(0.34 + i * 0.06).toFixed(2)}s`,
                      }}
                    />
                  </div>
                  <span
                    className={cn(
                      'text-[10.5px] font-semibold leading-none',
                      b.isToday ? 'text-paper' : 'text-paper/55',
                    )}
                  >
                    {weekdayShort(b.key)}
                  </span>
                </div>
              ))}
            </div>
          </div>

          {/* An outlet that failed leaves the figure partial — say so, and offer the retry. */}
          {daily.failedCount > 0 ? (
            <div className="flex items-center justify-between gap-3 rounded-2xl border border-warning-line bg-tint-warning px-3.5 py-2.5 text-[12.5px] font-medium text-amber-2">
              <span>{t('dashboardPhone.partialFailed', { count: daily.failedCount })}</span>
              <button
                type="button"
                onClick={daily.retryFailed}
                className="shrink-0 rounded-full bg-surface px-3 py-1 text-[12px] font-bold text-ink hover:bg-hover"
              >
                {t('dashboardPhone.retry')}
              </button>
            </div>
          ) : null}

          {/* Four figures about the day. */}
          <div className="rise-in grid grid-cols-2 gap-2.5" style={{ animationDelay: '0.3s' }}>
            {stats.map((s) => (
              <div
                key={s.key}
                className="rounded-[18px] border border-line bg-surface px-3.5 pb-3 pt-[13px]"
              >
                <div className="text-[11.5px] font-semibold text-ink-3">{s.label}</div>
                {s.value == null ? (
                  <div className="mt-1.5 h-[19px] w-16 animate-pulse rounded-md bg-ink-100" />
                ) : (
                  <div className="tnum mt-1.5 truncate font-mono text-[19px] font-bold leading-none tracking-[-0.02em] text-ink">
                    {s.value}
                  </div>
                )}
                <div className="mt-1.5 min-h-[14px] truncate text-[11px] font-medium text-ink-3">
                  {s.sub}
                </div>
              </div>
            ))}
          </div>

          {/* Brand-new company — first-sale prompt instead of empty figures (UX audit parity). */}
          {showFirstSale ? (
            <Card className="flex flex-col items-center gap-3 p-6 text-center">
              <span className="grid size-11 place-items-center rounded-full bg-emerald-tint text-emerald-2">
                <Store className="size-5" aria-hidden="true" />
              </span>
              <div>
                <div className="text-sm font-semibold text-ink">{t('dashboard.noSalesYet')}</div>
                <div className="mt-1 text-[13px] text-ink-3">{t('dashboard.noSalesYetHint')}</div>
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
                  className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-emerald-2 hover:underline"
                >
                  <BookOpen className="size-4" aria-hidden="true" />
                  {t('dashboard.openingShortcut')}
                </Link>
              ) : null}
            </Card>
          ) : null}

          {/* What needs a decision — only rows with something in them; no section when none. */}
          {tasks.length > 0 ? (
            <section className="rise-in" style={{ animationDelay: '0.4s' }}>
              <div className={SECTION_LABEL}>{t('dashboardPhone.needsAction')}</div>
              <div className={LIST_CARD}>
                {tasks.map((task) => {
                  const TaskIcon = task.icon
                  return (
                    <Link
                      key={task.key}
                      to={task.to}
                      viewTransition
                      className="flex min-h-[68px] w-full items-center gap-3 border-b border-line/60 px-[15px] py-[13px] text-left transition-colors last:border-b-0 hover:bg-hover active:bg-ink-50"
                    >
                      <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-ink-50 text-ink">
                        <TaskIcon className="size-[18px]" strokeWidth={1.8} aria-hidden="true" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-[14px] font-semibold leading-snug tracking-[-0.01em] text-ink">
                          {task.label}
                        </span>
                        <span className="mt-0.5 block truncate text-[12.5px] text-ink-3">
                          {task.sub}
                        </span>
                      </span>
                      <span className="tnum grid h-6 min-w-6 shrink-0 place-items-center rounded-full bg-ink-50 px-2 text-[12px] font-bold text-ink-2">
                        {integer.format(task.count)}
                      </span>
                      <ChevronRight
                        className="size-4 shrink-0 text-ink-300"
                        strokeWidth={2}
                        aria-hidden="true"
                      />
                    </Link>
                  )
                })}
              </div>
            </section>
          ) : null}

          {/* Today by outlet — bar = share of the busiest; pointless for a single outlet. */}
          {shares.length > 1 ? (
            <section className="rise-in" style={{ animationDelay: '0.5s' }}>
              <div className="flex items-baseline justify-between gap-2.5 px-1">
                <span className="text-[12px] font-semibold text-ink-3">
                  {t('dashboardPhone.perOutlet')}
                </span>
                <span className="text-[11.5px] font-medium text-ink-400">
                  {t('dashboardPhone.today')}
                </span>
              </div>
              <div className="mt-2 rounded-[18px] border border-line bg-surface px-4 pb-1 pt-4">
                {shares.map((o) => (
                  <div key={o.id} className="mb-3.5">
                    <div className="flex items-baseline gap-2.5">
                      <span className="min-w-0 flex-1 truncate text-[13.5px] font-semibold text-ink">
                        {o.name}
                      </span>
                      <span className="tnum shrink-0 font-mono text-[13.5px] font-semibold leading-none text-ink">
                        {formatMoney(o.net, currency, locale)}
                      </span>
                    </div>
                    {/* Ink, not a series colour: a magnitude bar in a list, drawn in the figure's ink. */}
                    <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-hover">
                      <div
                        className="bar-wide h-full rounded-full bg-emerald"
                        style={{ width: `${o.pct}%`, animationDelay: '0.55s' }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            </section>
          ) : null}

          {/* Best sellers today, across outlets. */}
          {topItems.length > 0 ? (
            <section className="rise-in" style={{ animationDelay: '0.6s' }}>
              <div className={SECTION_LABEL}>{t('dashboardPhone.topItems')}</div>
              <div className={LIST_CARD}>
                {topItems.map((item) => (
                  <div
                    key={item.rank}
                    className="flex min-h-[58px] items-center gap-3 border-b border-line/60 px-[15px] py-[11px] last:border-b-0"
                  >
                    <span className="tnum grid size-[22px] shrink-0 place-items-center rounded-[7px] bg-ink-50 font-mono text-[11px] font-bold text-ink-2">
                      {item.rank}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13.5px] font-semibold text-ink">
                        {item.name}
                      </span>
                      <span className="mt-0.5 block text-[12px] text-ink-3">
                        {t('dashboardPhone.sold', { count: item.soldQty })}
                      </span>
                    </span>
                    <span className="tnum shrink-0 font-mono text-[13px] font-semibold leading-none text-ink-2">
                      {formatMoney(item.revenueMinor, currency, locale)}
                    </span>
                  </div>
                ))}
              </div>
            </section>
          ) : null}

          {/* Four doors. */}
          {doors.length > 0 ? (
            <div
              className={cn(
                'rise-in grid gap-2.5',
                doors.length > 1 ? 'grid-cols-2' : 'grid-cols-1',
              )}
              style={{ animationDelay: '0.7s' }}
            >
              {doors.map((door) => {
                const DoorIcon = door.icon
                const body = (
                  <>
                    <DoorIcon
                      className="size-5 shrink-0 text-ink"
                      strokeWidth={1.8}
                      aria-hidden="true"
                    />
                    {door.label}
                  </>
                )
                return door.to ? (
                  <Link key={door.key} to={door.to} viewTransition className={TILE_CLASS}>
                    {body}
                  </Link>
                ) : (
                  <button
                    key={door.key}
                    type="button"
                    onClick={door.onClick}
                    className={TILE_CLASS}
                  >
                    {body}
                  </button>
                )
              })}
            </div>
          ) : null}
        </>
      )}

      {stocktakeOpen ? (
        <Suspense fallback={null}>
          <StandaloneStocktake onClose={() => setStocktakeOpen(false)} />
        </Suspense>
      ) : null}
    </div>
  )
}
