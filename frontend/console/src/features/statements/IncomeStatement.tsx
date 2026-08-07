import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Download, Printer, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatAmount, formatPercent } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { useIncomeStatement } from './api'
import { downloadCsv } from '@/lib/csv'
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

  const [period, setPeriod] = useState(currentPeriod())

  const query = useIncomeStatement({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    period,
    enabled: !!company,
  })

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

  // Top five expense accounts by amount — the breakdown card's rows.
  const topExpenses = [...(data?.expenseLines ?? [])]
    .sort((a, b) => b.netMinor - a.netMinor)
    .slice(0, 5)

  const exportCsv = () => {
    if (!data) return
    downloadCsv(`income-statement-${period}.csv`, [
      [company.name, t('statements.scopeAllUnits')],
      [t('statements.incomeTitle'), period, currency],
      [],
      [t('statements.revenue')],
      ...data.revenueLines.map((l) => [l.accountCode, l.netMinor]),
      [t('statements.totalRevenue'), data.totalRevenueMinor],
      [],
      [t('statements.expense')],
      ...data.expenseLines.map((l) => [l.accountCode, l.netMinor]),
      [t('statements.totalExpense'), data.totalExpenseMinor],
      [],
      [profit ? t('statements.netProfit') : t('statements.netLoss'), data.netMinor],
    ])
  }

  return (
    <div className="flex flex-col gap-5">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <EntityScope name={company.name} scope={t('statements.scopeAllUnits')} />
          <h1 className="font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
            {t('statements.incomeTitle')}
          </h1>
          <p className="mt-1.5 text-[15px] text-ink-3">{t('statements.incomeSubtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2.5 print:hidden">
          <PeriodNav
            period={period}
            locale={locale}
            onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
            onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
            prevLabel={t('statements.prevPeriod')}
            nextLabel={t('statements.nextPeriod')}
          />
          <Button variant="outline" onClick={() => window.print()} aria-label={t('statements.print')}>
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
        <Card className="flex justify-center p-10 text-emerald">
          <Spinner />
        </Card>
      ) : (
        <>
          {/* Summary cards */}
          <div className="grid gap-4 sm:grid-cols-3 print:grid-cols-3 print:gap-3">
            <SummaryCard
              chipClass="bg-brand-500"
              label={t('statements.revenue')}
              value={formatMoney(totalRevenue, currency, locale)}
            />
            <SummaryCard
              chipClass="bg-loss"
              label={t('statements.expense')}
              value={formatMoney(totalExpense, currency, locale)}
              note={t('statements.ofRevenue', { pct: formatPercent(expenseRatio, locale) })}
            />
            <SummaryCard
              chipClass="bg-profit"
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
                <span className="text-[12.5px] text-ink-3">{t('statements.topExpensesNote')}</span>
              </div>
              <div className="mt-[18px] flex flex-col gap-[15px]">
                {topExpenses.map((line) => {
                  const share = Math.max(0, line.netMinor) / totalExpense
                  return (
                    <div key={line.accountCode}>
                      <div className="mb-1.5 flex items-baseline justify-between gap-3">
                        <span className="min-w-0 truncate font-mono text-[13px] font-semibold text-ink">
                          {line.accountCode}
                        </span>
                        <span className="flex shrink-0 items-baseline gap-3">
                          <span className="tnum font-mono text-[13.5px] font-semibold text-ink">
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
          <div className="grid gap-5 lg:grid-cols-2">
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
    </div>
  )
}

