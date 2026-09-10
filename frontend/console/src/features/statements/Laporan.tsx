/**
 * Laporan — the phone reports screen (Native Laporan, 412×915). One shape repeated four times:
 * a tab row (Income · Balance · Cash flow · Expenses), one answer figure, a twelve-month chart that
 * IS the period control, then the supporting figures, the banners that only appear when something
 * needs saying, the ledger down to the last account, and two bottom sheets (drill-down, export).
 *
 * Tap a column and the WHOLE report moves to that month — hero, banners, tables. The desktop pages
 * step months with a ‹ › control; on a phone the chart already on screen does that job and shows
 * the shape of the year while it is at it.
 *
 * Nothing here is new logic. The three statement hooks, the Neraca's display rules
 * (`balanceSheetView.ts`), the drill-down rows (`incomeDetail.ts`), the account names
 * (`accountLabels.ts`), the tables (`parts.tsx LineSection`) and the CSV builders
 * (`statementsCsv.ts`) are the ones the desktop pages use. The one thing the mockup thought was
 * missing — a trend endpoint — is answered the way the dashboard already answers it: one query per
 * month, de-duplicated with the month on screen by an identical queryKey (`usePnlTrend`, and its two
 * new siblings in `./api`). Only the ACTIVE tab's window is fetched.
 *
 * The three routes stay: a tab switch is `navigate(…, { replace: true })`, so deep links, the
 * accountant's home (`/statements/income`) and the phone tab bar keep working, and BACK pops OUT of
 * the reports instead of walking back through tabs (ADR 0075). Because a tab switch is a route
 * change — a different lazy element, so this screen REMOUNTS — the selected month and chart type
 * live in the URL (`?period=`, `?chart=`), not in state: the month the reader picked on Income is
 * the month they see on Balance. Desktop (≥640px) never renders this.
 *
 * This screen renders INSIDE the Shell (its sticky topbar, its gutters, the phone tab bar), so like
 * DashboardPhone and MorePage it draws no chrome of its own: the title is an in-flow heading, not a
 * second sticky header.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { BarChart3, Check, ChevronRight, Download, FileText, LineChart, Printer, TriangleAlert } from 'lucide-react'
import { DialogOverlay } from '@/components/ui/Dialog'
import { Skeleton } from '@/components/ui/Skeleton'
import { useSession } from '@/lib/session'
import { cn } from '@/lib/cn'
import { localeOf } from '@/i18n'
import { formatAmount, formatMoney, formatPercent } from '@/lib/money'
import { printCurrentPage } from '@/lib/nativeShell'
import { currentPeriod, formatPeriod } from '@/lib/period'
import { downloadCsv } from '@/lib/csv'
import { usePnlTrend } from '@/features/dashboard/api'
import {
  useBalanceSheet,
  useBalanceSheetTrend,
  useCashFlow,
  useCashFlowTrend,
  useIncomeStatement,
} from './api'
import { accountLabel, accountLabelMap } from './accountLabels'
import { displayBalanceSheet } from './balanceSheetView'
import { EquationRow, LineSection, StatementEmptyState, type DisplayLine } from './parts'
import { PeriodChart } from './PeriodChart'
import { ShareBars } from './ShareBars'
import { incomeDetailTitle, type IncomeDetailKind } from './incomeDetail'
import { IncomeDetailBody } from './IncomeDetailBody'
import { seriesFailures, seriesTone, seriesValues, trailingPeriods, type TrendKey } from './periodChartMath'
import { DISPOSAL_PROCEEDS, balanceSheetCsv, cashFlowCsv, incomeCsv } from './statementsCsv'

export type ReportTab = 'pnl' | 'bs' | 'cf' | 'exp'

const TABS: ReportTab[] = ['pnl', 'bs', 'cf', 'exp']
const PATH: Record<ReportTab, string> = {
  pnl: '/statements/income',
  bs: '/statements/balance-sheet',
  cf: '/statements/cash-flow',
  exp: '/statements/income',
}
const PANEL_ID = 'laporan-panel'
const TREND_MONTHS = 12

const SECTION_LABEL = 'text-[10.5px] font-semibold uppercase tracking-[0.04em] text-ink-3'

export function Laporan({ tab }: { tab: ReportTab }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const { company } = useSession()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  // The window is the last twelve months up to TODAY, fixed; the selection moves within it. Re-
  // windowing on every tap would push the tapped month to the right edge and lose the way forward.
  const [windowEnd] = useState(currentPeriod)
  const periods = trailingPeriods(windowEnd, TREND_MONTHS)
  // Selection and chart type come from the URL (see the header comment); anything outside the
  // window — a stale link, a typo — falls back to today rather than to an empty chart.
  const requested = searchParams.get('period')
  const period = requested != null && periods.includes(requested) ? requested : windowEnd
  const chartType: 'bar' | 'line' = searchParams.get('chart') === 'line' ? 'line' : 'bar'
  const [showZeros, setShowZeros] = useState(false)
  const [sheet, setSheet] = useState<null | 'export' | IncomeDetailKind>(null)
  // Print runs AFTER the export sheet has left the DOM (window.print is synchronous — fired from the
  // sheet's own handler it would snapshot the scrim and a scroll-locked body). The effect below
  // runs after the commit that unmounts the sheet, and after its scroll lock has been released.
  const pendingPrint = useRef<string | null>(null)
  useEffect(() => {
    if (sheet != null || pendingPrint.current == null) return
    const title = pendingPrint.current
    pendingPrint.current = null
    printCurrentPage(title)
  }, [sheet])

  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''
  const ready = !!company
  const usesPnl = tab === 'pnl' || tab === 'exp'

  // Only the active tab fetches — its statement AND its twelve-month window. Switching tabs is a
  // fresh window, not three at once, and the month on screen shares a cache entry with its column.
  const income = useIncomeStatement({ companyId, actor, period, enabled: ready && usesPnl })
  const balance = useBalanceSheet({ companyId, actor, asOf: period, enabled: ready && tab === 'bs' })
  const cash = useCashFlow({ companyId, actor, period, enabled: ready && tab === 'cf' })
  const pnlTrend = usePnlTrend({
    companyId,
    actor,
    period: windowEnd,
    baseCurrency: company?.baseCurrency ?? '',
    months: TREND_MONTHS,
    enabled: ready && usesPnl,
  })
  const bsTrend = useBalanceSheetTrend({ companyId, actor, asOf: windowEnd, months: TREND_MONTHS, enabled: ready && tab === 'bs' })
  const cfTrend = useCashFlowTrend({ companyId, actor, period: windowEnd, months: TREND_MONTHS, enabled: ready && tab === 'cf' })
  const names = useMemo(() => accountLabelMap(t), [t])

  if (!company) {
    return <StatementEmptyState title={t('statements.noCompany')} hint={t('statements.noCompanyHint')} />
  }

  const active = usesPnl ? income : tab === 'bs' ? balance : cash
  const data = active.data ?? null
  const currency = data?.currency ?? company.baseCurrency
  const isError = active.isError
  const isLoading = active.isLoading && data == null
  const isEmpty = !isLoading && !isError && data == null
  // The previous month's figures stay on screen, dimmed, while the new month settles — never a
  // skeleton flash between two months (keepPreviousData + dataviz "refetch keeps the frame").
  const settling = active.isFetching && active.isPlaceholderData

  const trendKey: TrendKey = tab === 'pnl' ? 'net' : tab === 'bs' ? 'netWorth' : tab === 'cf' ? 'cash' : 'expense'
  const values = usesPnl
    ? seriesValues(pnlTrend, (d) => (trendKey === 'net' ? d.netMinor : d.expenseMinor))
    : tab === 'bs'
      ? seriesValues(bsTrend, (d) => d.totalEquityMinor)
      : seriesValues(cfTrend, (d) => d.netChangeInCashMinor)
  // A month that could not be loaded is a warning mark, not a gap — and it can be retried, one
  // column at a time (tap it) or all at once (the caption under the chart).
  const points: { period: string; failed: boolean; retry: () => void }[] = usesPnl ? pnlTrend : tab === 'bs' ? bsTrend : cfTrend
  const failed = seriesFailures(points)
  const failedCount = failed.filter(Boolean).length
  const retryMonth = (p: string) => points.find((x) => x.period === p)?.retry()
  const retryFailed = () => points.forEach((x) => x.failed && x.retry())

  const money = (minor: number) => formatMoney(minor, currency, locale)
  const amount = (minor: number) => formatAmount(minor, currency, locale)
  const periodText = formatPeriod(period, locale)

  // The URL carries only what differs from the default, so the plain routes stay plain.
  const query = (opts: { tab: ReportTab; period: string; chart: 'bar' | 'line' }) => {
    const q = new URLSearchParams()
    if (opts.tab === 'exp') q.set('tab', 'expense')
    if (opts.period !== windowEnd) q.set('period', opts.period)
    if (opts.chart === 'line') q.set('chart', 'line')
    const qs = q.toString()
    return qs ? `?${qs}` : ''
  }
  const goTab = (next: ReportTab) => {
    if (next === tab) return
    setSheet(null)
    navigate(`${PATH[next]}${query({ tab: next, period, chart: chartType })}`, { replace: true })
  }
  const selectMonth = (p: string) => {
    setSheet(null)
    setSearchParams(query({ tab, period: p, chart: chartType }), { replace: true })
  }
  const setChartType = (c: 'bar' | 'line') => setSearchParams(query({ tab, period, chart: c }), { replace: true })

  // ── Export ────────────────────────────────────────────────────────────────────────────────────
  const reportName =
    tab === 'bs' ? t('statements.balanceTitle') : tab === 'cf' ? t('statements.cashFlow.title') : t('statements.incomeTitle')
  const csvFile = () => {
    const ctx = { translate: t, companyName: company.name }
    if (tab === 'bs') return balance.data ? balanceSheetCsv(ctx, balance.data) : null
    if (tab === 'cf') return cash.data ? cashFlowCsv(ctx, cash.data) : null
    return income.data ? incomeCsv(ctx, income.data) : null
  }
  const exportFile = csvFile()

  const chart = (
    <div className="print:hidden">
      <div className="flex items-center justify-between gap-2.5 pt-4">
        <span className={SECTION_LABEL}>{t('statements.phone.trendCaption', { n: TREND_MONTHS })}</span>
        <div className="flex shrink-0 gap-[3px] rounded-[13px] bg-hover p-[3px]" role="group">
          {(['bar', 'line'] as const).map((kind) => {
            const on = chartType === kind
            return (
              <button
                key={kind}
                type="button"
                onClick={() => setChartType(kind)}
                aria-pressed={on}
                aria-label={t(`statements.phone.chart.${kind}`)}
                className={cn(
                  'grid h-9 w-11 place-items-center rounded-[10px] transition-colors',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                  on ? 'bg-surface text-ink shadow-sm' : 'text-ink-3',
                )}
              >
                {kind === 'bar' ? <BarChart3 className="size-4" aria-hidden="true" /> : <LineChart className="size-4" aria-hidden="true" />}
              </button>
            )
          })}
        </div>
      </div>
      <div className="-mx-5 pt-2.5">
        <PeriodChart
          periods={periods}
          values={values}
          failed={failed}
          selected={period}
          onSelect={selectMonth}
          onRetry={retryMonth}
          type={chartType}
          tone={(v) => seriesTone(trendKey, v)}
          formatValue={money}
          locale={locale}
        />
      </div>
      {failedCount > 0 ? (
        <div className="flex items-center gap-2 pt-2 text-[11.5px] font-medium text-amber">
          <TriangleAlert className="size-3.5 shrink-0" aria-hidden="true" />
          <span className="min-w-0 flex-1">
            {failedCount === 1
              ? t('statements.phone.trendFailedOne')
              : t('statements.phone.trendFailedMany', { count: failedCount })}
          </span>
          <button
            type="button"
            onClick={retryFailed}
            className="min-h-11 shrink-0 px-1 font-bold text-ink underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            {t('statements.phone.retry')}
          </button>
        </div>
      ) : null}
    </div>
  )

  return (
    <div className="flex flex-col">
      {/* Header: title + scope, the export action beside it. In flow — the Shell's topbar is the
          sticky one, and a root tab destination shows no back arrow (DashboardPhone, MorePage). */}
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="font-display text-[19px] font-bold leading-tight tracking-[-0.01em] text-ink">
            {t('statements.phone.title')}
          </h1>
          <p className="mt-0.5 truncate text-[12.5px] text-ink-3">
            {company.name} · {t('statements.scopeAllUnits')}
          </p>
        </div>
        <button
          type="button"
          data-testid="laporan-export"
          onClick={() => setSheet('export')}
          disabled={!exportFile}
          aria-label={t('statements.phone.export.title', { report: reportName })}
          className="-mr-2.5 -mt-1.5 grid size-11 shrink-0 place-items-center rounded-xl text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:opacity-40 print:hidden"
        >
          <Download className="size-[19px]" aria-hidden="true" />
        </button>
      </div>

      {/* Tabs — a pill row; the active one is ink. Full-bleed so the row scrolls edge to edge. */}
      <div
        role="tablist"
        className="-mx-5 flex gap-1.5 overflow-x-auto px-5 pb-3 pt-3.5 [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden print:hidden"
      >
        {TABS.map((key) => {
          const on = key === tab
          return (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={on}
              aria-controls={PANEL_ID}
              onClick={() => goTab(key)}
              className={cn(
                'h-10 shrink-0 whitespace-nowrap rounded-full px-3.5 text-[12px] transition-colors',
                'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                on ? 'bg-emerald font-bold text-on-emerald' : 'bg-hover font-semibold text-ink-2',
              )}
            >
              {t(`statements.phone.tab.${key}`)}
            </button>
          )
        })}
      </div>

      <div id={PANEL_ID} role="tabpanel">
      {isError ? (
        <div className="grid flex-1 place-items-center px-8 py-10 text-center">
          <div>
            <TriangleAlert className="mx-auto mb-2.5 size-[26px] text-loss" aria-hidden="true" />
            <div className="text-[14px] font-semibold text-loss">{t('statements.error')}</div>
            <button
              type="button"
              onClick={() => void active.refetch()}
              className="mt-5 min-h-12 rounded-[14px] bg-emerald px-5 text-[14px] font-bold text-on-emerald transition-transform active:scale-[.985] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            >
              {t('statements.phone.retry')}
            </button>
          </div>
        </div>
      ) : isLoading ? (
        <div className="flex flex-col gap-[18px] pt-2">
          <div className="flex flex-col gap-2">
            <Skeleton className="h-[11px] w-[38%] rounded-md" />
            <Skeleton className="h-[30px] w-[66%] rounded-lg" />
          </div>
          <Skeleton className="h-[132px] rounded-[14px]" />
          {[72, 54, 83, 61, 47, 76].map((w, i) => (
            <div key={i} className="flex items-center gap-3">
              <div className="h-3" style={{ width: `${w}%` }}>
                <Skeleton className="h-full rounded-md" />
              </div>
              <Skeleton className="ml-auto h-3 w-[84px] shrink-0 rounded-md" />
            </div>
          ))}
        </div>
      ) : isEmpty ? (
        <div className="flex flex-col">
          {chart}
          <div className="grid place-items-center px-8 py-12 text-center">
            <div>
              <div className="text-[14px] font-semibold text-ink">
                {t('statements.phone.emptyFor', { period: periodText })}
              </div>
              <div className="mt-2 text-[12px] font-medium text-ink-3">{t('statements.phone.emptyTap')}</div>
            </div>
          </div>
        </div>
      ) : (
        <div className={cn('pb-8 transition-opacity', settling && 'opacity-60')}>
          {tab === 'pnl' || tab === 'exp' ? (
            <IncomeView
              tab={tab}
              data={income.data!}
              money={money}
              amount={amount}
              locale={locale}
              periodText={periodText}
              chart={chart}
              onDetail={(k) => setSheet(k)}
            />
          ) : tab === 'bs' ? (
            <BalanceView
              data={balance.data!}
              money={money}
              currency={currency}
              locale={locale}
              periodText={periodText}
              chart={chart}
              showZeros={showZeros}
              onToggleZeros={() => setShowZeros((v) => !v)}
            />
          ) : (
            <CashView data={cash.data!} money={money} amount={amount} currency={currency} locale={locale} periodText={periodText} chart={chart} />
          )}
        </div>
      )}

      </div>

      {/* Drill-down sheet — the drawer's body, in the one Dialog primitive (a bottom sheet on phone). */}
      {sheet && sheet !== 'export' && income.data ? (
        <DialogOverlay onClose={() => setSheet(null)} ariaLabel={incomeDetailTitle(sheet, t)}>
          <div className="border-b border-line pb-3">
            <div className="text-[16px] font-bold leading-tight text-ink">{incomeDetailTitle(sheet, t)}</div>
            <div className="mt-0.5 font-mono text-[12px] text-ink-3">{periodText}</div>
          </div>
          <div className="pt-3.5">
            <IncomeDetailBody kind={sheet} data={income.data} names={names} currency={currency} locale={locale} size="sm" />
          </div>
        </DialogOverlay>
      ) : null}

      {/* Export sheet. */}
      {sheet === 'export' && exportFile ? (
        <DialogOverlay onClose={() => setSheet(null)} ariaLabel={t('statements.phone.export.title', { report: reportName })}>
          <div className="pb-2 text-[16px] font-bold text-ink">
            {t('statements.phone.export.title', { report: reportName })}
          </div>
          <div className="-mx-1 flex flex-col gap-0.5">
            <button
              type="button"
              onClick={() => {
                downloadCsv(exportFile.filename, exportFile.rows)
                setSheet(null)
              }}
              className="flex min-h-14 items-center gap-3 rounded-xl px-1 text-left transition-colors active:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            >
              <FileText className="size-[19px] shrink-0 text-ink-2" aria-hidden="true" />
              <span className="min-w-0 flex-1">
                <span className="block text-[14px] font-semibold text-ink">{t('statements.phone.export.csv')}</span>
                <span className="mt-0.5 block font-mono text-[12px] text-ink-3">{exportFile.filename}</span>
              </span>
            </button>
            <button
              type="button"
              onClick={() => {
                // Prints THIS page once the sheet is gone: the ledger sections keep every account,
                // zero balances included (parts.tsx `printOnly` rows); the tabs, the chart and the
                // actions are `print:hidden`, so the printout is the statement and nothing else.
                pendingPrint.current = exportFile.filename.replace(/\.csv$/, '')
                setSheet(null)
              }}
              className="flex min-h-14 items-center gap-3 rounded-xl px-1 text-left transition-colors active:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            >
              <Printer className="size-[19px] shrink-0 text-ink-2" aria-hidden="true" />
              <span className="min-w-0 flex-1">
                <span className="block text-[14px] font-semibold text-ink">{t('statements.phone.export.print')}</span>
                <span className="mt-0.5 block text-[12px] text-ink-3">{t('statements.phone.export.printNote')}</span>
              </span>
            </button>
          </div>
          <button
            type="button"
            onClick={() => setSheet(null)}
            className="mt-3.5 h-[54px] w-full rounded-[15px] bg-emerald text-[16px] font-bold text-on-emerald transition-transform active:scale-[.985] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            {t('statements.phone.export.close')}
          </button>
        </DialogOverlay>
      ) : null}
    </div>
  )
}

// ── Shared pieces ──────────────────────────────────────────────────────────────────────────────

function Hero({
  label,
  illustrative,
  value,
  tone,
  note,
  onTap,
}: {
  label: string
  illustrative: boolean
  value: string
  tone: 'profit' | 'loss' | 'ink'
  note?: string
  onTap?: () => void
}) {
  const { t } = useTranslation()
  const color = tone === 'profit' ? 'text-profit-ink' : tone === 'loss' ? 'text-loss' : 'text-ink'
  const body = (
    <>
      <span className={cn('tnum font-mono text-[30px] font-bold leading-none tracking-[-.02em]', color)}>
        {value}
      </span>
      {note ? (
        <span className={cn('flex items-center gap-1.5 text-[12px] font-semibold', tone === 'ink' ? 'text-ink-3' : color)}>
          {note}
          {onTap ? <ChevronRight className="size-[13px]" aria-hidden="true" /> : null}
        </span>
      ) : null}
    </>
  )
  return (
    <div className="pt-1.5">
      <div className="flex items-center gap-2">
        <span className="text-[12px] font-semibold text-ink-3">{label}</span>
        {illustrative ? (
          <span className="flex h-[19px] items-center gap-1 rounded-full border border-warning-line px-1.5 text-[10.5px] font-bold text-amber">
            <TriangleAlert className="size-2.5" aria-hidden="true" />
            {t('statements.illustrative')}
          </span>
        ) : null}
      </div>
      {onTap ? (
        <button
          type="button"
          data-testid="laporan-hero"
          onClick={onTap}
          className="mt-[7px] flex min-h-11 w-full flex-col items-start gap-[7px] rounded-lg py-0.5 text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          {body}
        </button>
      ) : (
        <div className="mt-[7px] flex min-h-11 flex-col items-start gap-[7px] py-0.5">{body}</div>
      )}
    </div>
  )
}

function Figure({
  label,
  value,
  tone = 'ink',
  note,
  size = 'md',
  onClick,
}: {
  label: string
  value: string
  tone?: 'profit' | 'loss' | 'ink'
  note?: string
  /** `sm` is the three-up row: nine-digit rupiah must still fit a 360px phone. */
  size?: 'md' | 'sm'
  onClick?: () => void
}) {
  const color = tone === 'profit' ? 'text-profit-ink' : tone === 'loss' ? 'text-loss' : 'text-ink'
  const small = size === 'sm'
  const inner = (
    <>
      <span className={SECTION_LABEL}>{label}</span>
      <span className={cn('tnum whitespace-nowrap font-mono font-bold leading-tight', small ? 'text-[12px]' : 'text-[14px]', color)}>
        {value}
      </span>
      {note ? <span className="text-[10.5px] font-medium leading-tight text-ink-3">{note}</span> : null}
    </>
  )
  const cls = cn(
    'flex min-h-11 min-w-0 flex-1 flex-col items-start gap-1 overflow-hidden rounded-[14px] bg-hover py-2.5 text-left',
    small ? 'px-2.5' : 'px-3',
  )
  return onClick ? (
    <button type="button" onClick={onClick} className={cn(cls, 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald')}>
      {inner}
    </button>
  ) : (
    <div className={cls}>{inner}</div>
  )
}

function Banner({
  tone,
  title,
  body,
  amount,
}: {
  tone: 'warning' | 'loss' | 'profit'
  title: string
  body?: string
  amount?: string
}) {
  const bg = tone === 'warning' ? 'bg-tint-warning' : tone === 'loss' ? 'bg-tint-loss' : 'bg-tint-profit'
  const ink = tone === 'warning' ? 'text-amber' : tone === 'loss' ? 'text-loss-ink' : 'text-profit-ink'
  return (
    <div className={cn('flex gap-[11px] rounded-2xl p-3.5', bg)}>
      <span className={cn('mt-px shrink-0', ink)}>
        {tone === 'profit' ? <Check className="size-[18px]" strokeWidth={2.6} aria-hidden="true" /> : <TriangleAlert className="size-[18px]" aria-hidden="true" />}
      </span>
      <div className="min-w-0 flex-1">
        <div className={cn('text-[13px] font-bold leading-[1.35]', ink)}>{title}</div>
        {body ? <div className="mt-1 text-[12px] leading-[1.5] text-ink-2">{body}</div> : null}
      </div>
      {amount ? <span className={cn('tnum shrink-0 font-mono text-[13px] font-bold', ink)}>{amount}</span> : null}
    </div>
  )
}

function Section({ children }: { children: React.ReactNode }) {
  return <div className="pt-6">{children}</div>
}

// ── Income statement + Expenses tab ────────────────────────────────────────────────────────────

function IncomeView({
  tab,
  data,
  money,
  amount,
  locale,
  periodText,
  chart,
  onDetail,
}: {
  tab: 'pnl' | 'exp'
  data: NonNullable<ReturnType<typeof useIncomeStatement>['data']>
  money: (m: number) => string
  amount: (m: number) => string
  locale: string
  periodText: string
  chart: React.ReactNode
  onDetail: (k: IncomeDetailKind) => void
}) {
  const { t } = useTranslation()
  const net = data.netMinor
  const profit = net >= 0
  const revenue = data.totalRevenueMinor
  const expense = data.totalExpenseMinor
  const expenseRatio = revenue > 0 ? expense / revenue : 0
  const name = (code: string) => accountLabel(t, code) ?? code

  const lines = (ls: typeof data.revenueLines): DisplayLine[] =>
    ls.map((l) => ({ accountCode: l.accountCode, amountMinor: l.netMinor }))

  if (tab === 'exp') {
    // Every expense account, largest first. Contra lines keep their signed share (negative), so the
    // percentage tells the truth; the bar simply has no length to draw.
    const rows = [...data.expenseLines]
      .sort((a, b) => b.netMinor - a.netMinor)
      .map((l) => ({ key: l.accountCode, name: name(l.accountCode), code: l.accountCode, amountMinor: l.netMinor, share: expense !== 0 ? l.netMinor / expense : 0 }))
    return (
      <>
        <Hero
          label={`${t('statements.expense')} · ${periodText}`}
          illustrative={data.usesIllustrativeRules}
          value={money(expense)}
          tone="ink"
          note={t('statements.ofRevenue', { pct: formatPercent(expenseRatio, locale) })}
        />
        {chart}
        <Section>
          <div className="flex items-baseline justify-between gap-2.5">
            <span className={SECTION_LABEL}>{t('statements.phone.expenseTab.title')}</span>
            <span className="text-[10.5px] text-ink-3">{t('statements.phone.expenseTab.note')}</span>
          </div>
          <ShareBars size="sm" className="mt-3.5" rows={rows} currency={data.currency} locale={locale} tone="loss" />
        </Section>
      </>
    )
  }

  // Top five expense accounts by amount — contra lines (netMinor < 0) net the total down but are
  // not themselves the largest expenses. Share is of TOTAL EXPENSE, clamped for the bar.
  const top = data.expenseLines
    .filter((l) => l.netMinor > 0)
    .sort((a, b) => b.netMinor - a.netMinor)
    .slice(0, 5)
    .map((l) => ({ key: l.accountCode, name: name(l.accountCode), code: l.accountCode, amountMinor: l.netMinor, share: expense > 0 ? Math.min(1, l.netMinor / expense) : 0 }))

  return (
    <>
      <Hero
        label={`${profit ? t('statements.netProfit') : t('statements.netLoss')} · ${periodText}`}
        illustrative={data.usesIllustrativeRules}
        value={money(net)}
        tone={profit ? 'profit' : 'loss'}
        note={revenue > 0 ? t('statements.marginPct', { pct: formatPercent(net / revenue, locale) }) : undefined}
        onTap={() => onDetail('net')}
      />
      {chart}
      <div className="flex gap-2.5 pt-[18px]">
        <Figure label={t('statements.revenue')} value={amount(revenue)} onClick={() => onDetail('revenue')} />
        <Figure
          label={t('statements.expense')}
          value={amount(expense)}
          note={t('statements.ofRevenue', { pct: formatPercent(expenseRatio, locale) })}
          onClick={() => onDetail('expense')}
        />
      </div>
      {top.length > 0 && expense > 0 ? (
        <Section>
          <div className="flex items-baseline justify-between gap-2.5">
            <span className={SECTION_LABEL}>{t('statements.topExpenses')}</span>
            <span className="text-[10.5px] text-ink-3">{t('statements.topExpensesNote')}</span>
          </div>
          <ShareBars size="sm" className="mt-3.5" rows={top} currency={data.currency} locale={locale} tone="loss" />
        </Section>
      ) : null}
      <Section>
        <LineSection
          heading={t('statements.revenueAccounts')}
          lines={lines(data.revenueLines)}
          totalLabel={t('statements.totalRevenue')}
          totalMinor={revenue}
          currency={data.currency}
          locale={locale}
          emptyLabel={t('statements.noLines')}
          format={formatAmount}
        />
      </Section>
      <Section>
        <LineSection
          heading={t('statements.expenseAccounts')}
          lines={lines(data.expenseLines)}
          totalLabel={t('statements.totalExpense')}
          totalMinor={expense}
          currency={data.currency}
          locale={locale}
          emptyLabel={t('statements.noLines')}
          format={formatAmount}
        />
      </Section>
    </>
  )
}

// ── Balance sheet ──────────────────────────────────────────────────────────────────────────────

function BalanceView({
  data,
  money,
  currency,
  locale,
  periodText,
  chart,
  showZeros,
  onToggleZeros,
}: {
  data: NonNullable<ReturnType<typeof useBalanceSheet>['data']>
  money: (m: number) => string
  currency: string
  locale: string
  periodText: string
  chart: React.ReactNode
  showZeros: boolean
  onToggleZeros: () => void
}) {
  const { t } = useTranslation()
  const totalAssets = data.totalAssetsMinor
  const totalLiabilities = data.totalLiabilitiesMinor
  const totalEquity = data.totalEquityMinor
  const delta = totalAssets - data.totalLiabilitiesAndEquityMinor
  const balanced = delta === 0

  const view = displayBalanceSheet(data, { showZeros })
  const toLine = (l: (typeof view.liabilityLines)[number]): DisplayLine => ({
    accountCode: l.accountCode,
    label: l.labelKey ? t(l.labelKey) : undefined,
    amountMinor: l.amountMinor,
    flagged: l.flagged,
    printOnly: l.printOnly,
  })

  /** "n accounts worth nothing are hidden · Show" — the sentence tracks the state. */
  const zeroFootnote = (hidden: number) =>
    hidden === 0 ? undefined : (
      <p className="text-[10.5px] leading-[1.5] text-ink-3 print:hidden">
        {showZeros
          ? hidden === 1
            ? t('statements.zeroShownOne')
            : t('statements.zeroShownMany', { count: hidden })
          : hidden === 1
            ? t('statements.zeroHiddenOne')
            : t('statements.zeroHiddenMany', { count: hidden })}{' '}
        <button
          type="button"
          onClick={onToggleZeros}
          className="min-h-11 px-0.5 font-bold text-ink underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          {showZeros ? t('statements.zeroHide') : t('statements.zeroShow')}
        </button>
      </p>
    )

  return (
    <>
      <Hero
        label={`${t('statements.netWorth')} · ${periodText}`}
        illustrative={data.usesIllustrativeRules}
        value={money(totalEquity)}
        tone="ink"
        note={balanced ? t('statements.phone.netWorthNote') : undefined}
      />
      {chart}
      {!balanced || view.flagged.length > 0 ? (
        <div className="flex flex-col gap-2.5 pt-[18px]">
          {!balanced ? <Banner tone="warning" title={t('statements.unbalancedTitle')} amount={money(delta)} /> : null}
          {view.flagged.length > 0 ? (
            <Banner
              tone="loss"
              title={
                view.flagged.length === 1
                  ? t('statements.unnatural.oneTitle', {
                      name: accountLabel(t, view.flagged[0].accountCode) ?? view.flagged[0].accountCode,
                      amount: money(Math.abs(view.flagged[0].balanceMinor)),
                    })
                  : t('statements.unnatural.manyTitle', { count: view.flagged.length })
              }
              body={t('statements.unnatural.body')}
            />
          ) : null}
        </div>
      ) : null}
      <div className="pt-5">
        <div className={SECTION_LABEL}>{t('statements.phone.whereFrom')}</div>
        <div className="mt-[11px] flex flex-col gap-2.5">
          <EquationRow label={t('statements.plain.assets')} value={money(totalAssets)} />
          <EquationRow op="−" label={t('statements.plain.liabilities')} value={money(totalLiabilities)} />
          {balanced ? null : <EquationRow op="−" label={t('statements.plain.difference')} value={money(delta)} tone="warning" />}
          <EquationRow op="=" label={t('statements.plain.equity')} value={money(totalEquity)} answer />
        </div>
        {balanced ? (
          <span className="mt-3 inline-flex h-6 items-center gap-1.5 rounded-full bg-tint-profit px-2.5 text-[11px] font-bold text-profit-ink">
            <Check className="size-[11px]" strokeWidth={3.2} aria-hidden="true" />
            {t('statements.checksOut')}
          </span>
        ) : null}
      </div>
      <Section>
        <LineSection
          heading={t('statements.plain.assets')}
          groups={view.assetGroups.map((g) => ({ label: t(g.labelKey), lines: g.lines.map(toLine), subtotalMinor: g.subtotalMinor }))}
          totalLabel={t('statements.plain.totalAssets')}
          totalMinor={totalAssets}
          currency={currency}
          locale={locale}
          emptyLabel={t('statements.noLines')}
          format={formatAmount}
          footnote={zeroFootnote(view.hiddenAssets)}
        />
      </Section>
      <Section>
        <LineSection
          heading={t('statements.plain.liabilities')}
          lines={view.liabilityLines.map(toLine)}
          totalLabel={t('statements.plain.totalLiabilities')}
          totalMinor={totalLiabilities}
          currency={currency}
          locale={locale}
          emptyLabel={t('statements.noLines')}
          format={formatAmount}
          footnote={zeroFootnote(view.hiddenLiabilities)}
        />
      </Section>
      <Section>
        <LineSection
          heading={t('statements.plain.equity')}
          lines={view.equityLines.map(toLine)}
          totalLabel={t('statements.plain.totalEquity')}
          totalMinor={totalEquity}
          currency={currency}
          locale={locale}
          emptyLabel={t('statements.noLines')}
          format={formatAmount}
          footnote={zeroFootnote(view.hiddenEquity)}
        />
      </Section>
    </>
  )
}

// ── Cash flow ──────────────────────────────────────────────────────────────────────────────────

function CashView({
  data,
  money,
  amount,
  currency,
  locale,
  periodText,
  chart,
}: {
  data: NonNullable<ReturnType<typeof useCashFlow>['data']>
  money: (m: number) => string
  amount: (m: number) => string
  currency: string
  locale: string
  periodText: string
  chart: React.ReactNode
}) {
  const { t } = useTranslation()
  const net = data.netChangeInCashMinor
  const operating: DisplayLine[] = [
    { accountCode: '', label: t('statements.cashFlow.netIncome'), amountMinor: data.netIncomeMinor },
    ...data.operatingLines.map((l) => ({ accountCode: l.accountCode, amountMinor: l.amountMinor })),
  ]
  const investing: DisplayLine[] = data.investingLines.map((l) =>
    l.accountCode === DISPOSAL_PROCEEDS
      ? { accountCode: '', label: t('statements.cashFlow.disposalProceeds'), amountMinor: l.amountMinor }
      : { accountCode: l.accountCode, amountMinor: l.amountMinor },
  )
  const financing: DisplayLine[] = data.financingLines.map((l) => ({ accountCode: l.accountCode, amountMinor: l.amountMinor }))

  return (
    <>
      <Hero
        label={`${t('statements.cashFlow.netChange')} · ${periodText}`}
        illustrative={data.usesIllustrativeRules}
        value={money(net)}
        tone={net >= 0 ? 'profit' : 'loss'}
        note={t('statements.phone.cashNote')}
      />
      {chart}
      <div className="flex gap-2 pt-[18px]">
        <Figure size="sm" label={t('statements.cashFlow.operating')} value={amount(data.cashFromOperatingMinor)} tone={data.cashFromOperatingMinor >= 0 ? 'profit' : 'loss'} />
        <Figure size="sm" label={t('statements.cashFlow.investing')} value={amount(data.cashFromInvestingMinor)} />
        <Figure size="sm" label={t('statements.cashFlow.financing')} value={amount(data.cashFromFinancingMinor)} />
      </div>
      <div className="pt-[18px]">
        {data.reconciled ? (
          <Banner tone="profit" title={t('statements.cashFlow.reconciled')} amount={money(data.cashMovementMinor)} />
        ) : (
          <Banner tone="loss" title={t('statements.cashFlow.notReconciled')} body={t('statements.cashFlow.reconcileHint')} amount={money(data.cashMovementMinor)} />
        )}
      </div>
      <Section>
        <LineSection heading={t('statements.cashFlow.operating')} lines={operating} totalLabel={t('statements.cashFlow.fromOperating')} totalMinor={data.cashFromOperatingMinor} currency={currency} locale={locale} emptyLabel={t('statements.noLines')} format={formatAmount} />
      </Section>
      <Section>
        <LineSection heading={t('statements.cashFlow.investing')} lines={investing} totalLabel={t('statements.cashFlow.fromInvesting')} totalMinor={data.cashFromInvestingMinor} currency={currency} locale={locale} emptyLabel={t('statements.cashFlow.noInvesting')} format={formatAmount} />
      </Section>
      <Section>
        <LineSection heading={t('statements.cashFlow.financing')} lines={financing} totalLabel={t('statements.cashFlow.fromFinancing')} totalMinor={data.cashFromFinancingMinor} currency={currency} locale={locale} emptyLabel={t('statements.cashFlow.noFinancing')} format={formatAmount} />
      </Section>
    </>
  )
}
