import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Download, Printer, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ListSkeleton, StatCardsSkeleton } from '@/components/ui/Skeleton'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatAmount, formatPercent } from '@/lib/money'
import { printCurrentPage } from '@/lib/nativeShell'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { useIncomeStatement } from './api'
import { downloadCsv } from '@/lib/csv'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { Laporan } from './Laporan'
import { useSearchParams } from 'react-router-dom'
import { incomeCsv } from './statementsCsv'
import { accountLabel, accountLabelMap } from './accountLabels'
import { IncomeDetailDrawer, type IncomeDetailKind } from './IncomeDetailDrawer'
import { EntityScope, LineSection, PeriodNav, StatementEmptyState, SummaryCard } from './parts'

/**
 * Income Statement (Laba Rugi) — summary cards, then the largest-expenses breakdown, then the
 * account tables. Native Console Web design: the old composition chart always drew revenue as
 * 100%, so a full card carried only two real numbers — replaced by the per-account expense
 * breakdown, which answers the actual question: where did the money go. Green still means profit
 * only. All data hooks, queries, and existing i18n keys are preserved unchanged.
 */
export function IncomeStatement() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  // Native Laporan: below 640px the three statements are one screen with tabs; this route carries
  // both the Income tab and (via ?tab=expense) the Expenses tab. Every hook below still runs first —
  // a hook count that changed with viewport width would crash the page at the breakpoint.
  const isPhone = useIsPhone()
  const [searchParams] = useSearchParams()
  const phoneTab = searchParams.get('tab') === 'expense' ? 'exp' : 'pnl'

  const [period, setPeriod] = useState(currentPeriod())
  // Which summary card's drill-down drawer is open (null = none). Cleared on close / period change.
  const [detail, setDetail] = useState<IncomeDetailKind | null>(null)

  const query = useIncomeStatement({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    period,
    enabled: !!company,
  })

  // Chart-of-accounts names for the drill-down drawer: code → localized name (falls back to the
  // code). Read from the console's own account-label map rather than the /budgets/accounts
  // endpoint that used to back this: that endpoint is FINANCE_ROLES-gated, so a manager — who may
  // read this page — saw bare codes here, and the names it serves are English-only regardless.
  const accountNames = useMemo(() => accountLabelMap(t), [t])

  if (isPhone) return <Laporan tab={phoneTab} />

  if (!company) {
    return (
      <StatementEmptyState
        title={t('statements.noCompany')}
        hint={t('statements.noCompanyHint')}
      />
    )
  }

  const data = query.data ?? null
  const currency = data?.currency ?? company.baseCurrency
  const net = data?.netMinor ?? 0
  const profit = net >= 0
  const showEmpty = !query.isLoading && !query.isError && data == null

  const totalRevenue = data?.totalRevenueMinor ?? 0
  const totalExpense = data?.totalExpenseMinor ?? 0
  const expenseRatio = totalRevenue > 0 ? totalExpense / totalRevenue : 0

  // Top five expense accounts by amount — the breakdown card's rows. Contra lines (netMinor < 0)
  // are excluded: they net the TOTAL down but are not "largest expenses" themselves.
  const topExpenses = (data?.expenseLines ?? [])
    .filter((l) => l.netMinor > 0)
    .sort((a, b) => b.netMinor - a.netMinor)
    .slice(0, 5)

  // Built by the shared, tested builder (statementsCsv.ts) — one source for this button and the
  // phone Export sheet, so the column contract can never drift between them.
  const exportCsv = () => {
    if (!data) return
    const file = incomeCsv({ translate: t, companyName: company.name }, data)
    downloadCsv(file.filename, file.rows)
  }

  return (
    <div className="flex flex-col gap-5">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <EntityScope name={company.name} scope={t('statements.scopeAllUnits')} />
          <h1 className="font-display text-2xl font-extrabold tracking-[-0.02em] text-ink">
            {t('statements.incomeTitle')}
          </h1>
          <p className="mt-1.5 text-base text-ink-3">{t('statements.incomeSubtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2.5 print:hidden max-sm:w-full max-sm:justify-end">
          <div className="max-sm:w-full">
            <PeriodNav
              period={period}
              locale={locale}
              onPrev={() => {
                setDetail(null)
                setPeriod((p) => shiftPeriod(p, -1))
              }}
              onNext={() => {
                setDetail(null)
                setPeriod((p) => shiftPeriod(p, 1))
              }}
              prevLabel={t('statements.prevPeriod')}
              nextLabel={t('statements.nextPeriod')}
            />
          </div>
          <Button variant="outline" onClick={() => printCurrentPage('income-statement')} aria-label={t('statements.print')}>
            <Printer className="size-[15px]" aria-hidden />
            <span className="max-sm:hidden">{t('statements.print')}</span>
          </Button>
          <Button onClick={exportCsv} disabled={!data} aria-label={t('statements.export')}>
            <Download className="size-[15px]" aria-hidden />
            <span className="max-sm:hidden">{t('statements.export')}</span>
          </Button>
        </div>
      </div>

      {/* Illustrative badge */}
      {data?.usesIllustrativeRules ? (
        <div>
          <Badge tone="amber">
            <TriangleAlert className="size-3" /> {t('statements.illustrative')}
          </Badge>
        </div>
      ) : null}

      {/* Error / empty / content */}
      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">{t('statements.error')}</Card>
      ) : showEmpty ? (
        <StatementEmptyState title={t('statements.noData')} hint={t('statements.noDataHint')} />
      ) : query.isLoading && !data ? (
        <>
          <StatCardsSkeleton cards={3} />
          <ListSkeleton rows={5} className="rounded-[20px]" />
          <div className="grid grid-cols-[minmax(0,1fr)] gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <ListSkeleton rows={5} className="rounded-[20px]" />
            <ListSkeleton rows={5} className="rounded-[20px]" />
          </div>
        </>
      ) : (
        <>
          {/* Summary cards */}
          <div className="grid gap-4 sm:grid-cols-3 print:grid-cols-3 print:gap-3">
            <SummaryCard
              chipClass="bg-emerald"
              label={t('statements.revenue')}
              value={formatMoney(totalRevenue, currency, locale)}
              onClick={() => setDetail('revenue')}
              detailLabel={t('statements.viewDetail')}
            />
            <SummaryCard
              chipClass="bg-loss"
              label={t('statements.expense')}
              value={formatMoney(totalExpense, currency, locale)}
              note={t('statements.ofRevenue', { pct: formatPercent(expenseRatio, locale) })}
              onClick={() => setDetail('expense')}
              detailLabel={t('statements.viewDetail')}
            />
            <SummaryCard
              chipClass={profit ? 'bg-profit' : 'bg-loss'}
              label={profit ? t('statements.netProfit') : t('statements.netLoss')}
              value={formatMoney(net, currency, locale)}
              valueClass={profit ? 'text-profit-ink' : 'text-loss'}
              note={
                totalRevenue > 0
                  ? t('statements.marginPct', {
                      pct: formatPercent(net / totalRevenue, locale),
                    })
                  : undefined
              }
              noteClass={profit ? 'text-profit-ink' : 'text-loss'}
              emphatic
              onClick={() => setDetail('net')}
              detailLabel={t('statements.viewDetail')}
            />
          </div>

          {/* Largest expenses — each account's share of TOTAL EXPENSE (not of revenue), so every
              row carries information the summary cards don't already state. */}
          {topExpenses.length > 0 && totalExpense > 0 ? (
            <Card className="p-6">
              <div className="flex flex-wrap items-baseline justify-between gap-3">
                <h2 className="font-display text-lg font-semibold tracking-[-0.01em] text-ink">
                  {t('statements.topExpenses')}
                </h2>
                <span className="text-xs text-ink-3">{t('statements.topExpensesNote')}</span>
              </div>
              <div className="mt-[18px] flex flex-col gap-[15px]">
                {topExpenses.map((line) => {
                  // Clamped: with contra lines netting totalExpense down, a raw share can top 100%.
                  const share = Math.min(1, line.netMinor / totalExpense)
                  const name = accountLabel(t, line.accountCode)
                  return (
                    <div key={line.accountCode}>
                      <div className="mb-1.5 flex items-baseline justify-between gap-3">
                        {/* Name leads; the code trails as a quiet chip so the row is still
                            traceable back to the ledger. An unnamed account shows its code alone. */}
                        <span className="min-w-0 truncate text-sm font-semibold text-ink">
                          {name ?? line.accountCode}
                          {name ? (
                            <span className="ml-1.5 font-mono text-2xs font-normal text-ink-3">
                              {line.accountCode}
                            </span>
                          ) : null}
                        </span>
                        <span className="flex shrink-0 items-baseline gap-3">
                          <span className="tnum font-mono text-sm font-semibold text-ink">
                            {formatAmount(line.netMinor, currency, locale)}
                          </span>
                          <span className="tnum w-[42px] text-right font-mono text-xs text-ink-3">
                            {formatPercent(share, locale)}
                          </span>
                        </span>
                      </div>
                      <div className="h-2 overflow-hidden rounded-full bg-ink-50">
                        <div
                          className="h-full rounded-full bg-loss"
                          style={{ width: `${Math.min(100, share * 100)}%` }}
                        />
                      </div>
                    </div>
                  )
                })}
              </div>
            </Card>
          ) : null}

          {/* Account tables */}
          <div className="grid grid-cols-[minmax(0,1fr)] gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <Card className="p-6">
              <LineSection
                heading={t('statements.revenueAccounts')}
                lines={(data?.revenueLines ?? []).map((l) => ({
                  accountCode: l.accountCode,
                  amountMinor: l.netMinor,
                }))}
                totalLabel={t('statements.totalRevenue')}
                totalMinor={totalRevenue}
                currency={currency}
                locale={locale}
                emptyLabel={t('statements.noLines')}
                format={formatAmount}
              />
            </Card>
            <Card className="p-6">
              <LineSection
                heading={t('statements.expenseAccounts')}
                lines={(data?.expenseLines ?? []).map((l) => ({
                  accountCode: l.accountCode,
                  amountMinor: l.netMinor,
                }))}
                totalLabel={t('statements.totalExpense')}
                totalMinor={totalExpense}
                currency={currency}
                locale={locale}
                emptyLabel={t('statements.noLines')}
                format={formatAmount}
              />
            </Card>
          </div>
        </>
      )}

      {/* Card drill-down (owner request): a clicked summary card opens the per-account breakdown. */}
      {detail && data ? (
        <IncomeDetailDrawer
          kind={detail}
          data={data}
          names={accountNames}
          currency={currency}
          locale={locale}
          onClose={() => setDetail(null)}
        />
      ) : null}
    </div>
  )
}

