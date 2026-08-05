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
 * Income Statement (Laba Rugi) — design 2b: the parchment palette retired.
 * Summary cards, then a composition chart whose series carry meaning (cyan revenue,
 * red expense, green net — green means profit only), then the account tables.
 * All data hooks, queries, and existing i18n keys are preserved unchanged.
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
  const netRatio = totalRevenue > 0 ? Math.max(0, net) / totalRevenue : 0

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
          <Button variant="outline" onClick={() => window.print()}>
            <Printer className="size-[15px]" aria-hidden />
            {t('statements.print')}
          </Button>
          <Button onClick={exportCsv} disabled={!data}>
            <Download className="size-[15px]" aria-hidden />
            {t('statements.export')}
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

          {/* Composition — gridlines are ink, the series carry the meaning */}
          <Card className="p-6">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 className="font-display text-lg font-semibold tracking-[-0.01em] text-ink">
                {t('statements.composition')}
              </h2>
              <div className="flex gap-4 text-xs text-ink-3">
                <LegendSwatch className="bg-brand-500" label={t('statements.revenue')} />
                <LegendSwatch className="bg-loss" label={t('statements.expense')} />
                <LegendSwatch className="bg-profit" label={t('statements.net')} />
              </div>
            </div>
            <div className="relative mt-[18px] h-[188px]">
              <div aria-hidden className="absolute inset-0 flex flex-col justify-between">
                <span className="h-px bg-line" />
                <span className="h-px bg-ink-50" />
                <span className="h-px bg-ink-50" />
                <span className="h-px bg-ink-50" />
                <span className="h-px bg-line" />
              </div>
              <div className="relative flex h-full items-end justify-around gap-10 px-6 sm:px-14">
                <CompositionBar className="bg-brand-500" height={totalRevenue > 0 ? 1 : 0} />
                <CompositionBar className="bg-loss" height={expenseRatio} />
                <CompositionBar className="bg-profit" height={netRatio} />
              </div>
            </div>
            <div className="flex justify-around gap-10 px-6 pt-2.5 sm:px-14">
              <span className="max-w-[150px] flex-1 text-center text-[13px] font-semibold text-ink-2">
                {t('statements.revenue')}
              </span>
              <span className="max-w-[150px] flex-1 text-center text-[13px] font-semibold text-ink-2">
                {t('statements.expense')}
              </span>
              <span className="max-w-[150px] flex-1 text-center text-[13px] font-semibold text-ink-2">
                {t('statements.net')}
              </span>
            </div>
          </Card>

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

function LegendSwatch({ className, label }: { className: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`size-2.5 rounded-[3px] ${className}`} aria-hidden />
      {label}
    </span>
  )
}

function CompositionBar({ className, height }: { className: string; height: number }) {
  return (
    <div className="flex h-full max-w-[150px] flex-1 items-end">
      <div
        className={`w-full rounded-t-md ${className}`}
        style={{ height: `${Math.min(100, Math.max(0, height * 100))}%` }}
      />
    </div>
  )
}
