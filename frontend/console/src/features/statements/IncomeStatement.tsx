import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatAmount } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { useIncomeStatement } from './api'
import { PeriodNav, StatementEmptyState } from './parts'

/**
 * Income Statement (Laba Rugi) — restyled to the Native design system.
 * Two-column layout: left = revenue/expense line table with net profit highlight;
 * right = expense composition stacked bar + legend.
 * All data hooks, queries, and i18n keys are preserved unchanged.
 */

const COMPOSITION_COLORS = [
  '#16b364',
  '#2ebc72',
  '#5fcb8b',
  '#97deb1',
  '#f5a623',
  '#a8b0bc',
]

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

  // Derive expense composition shares from the expense line items the API returns
  const expenseLines = data?.expenseLines ?? []
  const totalExpense = data?.totalExpenseMinor ?? 0
  const compositionSegments = expenseLines.map((l, i) => ({
    accountCode: l.accountCode,
    amountMinor: l.netMinor,
    color: COMPOSITION_COLORS[i % COMPOSITION_COLORS.length],
    share: totalExpense > 0 ? l.netMinor / totalExpense : 0,
  }))

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('statements.incomeTitle')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('statements.incomeSubtitle')}</p>
        </div>
        <PeriodNav
          period={period}
          locale={locale}
          onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
          onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
          prevLabel={t('statements.prevPeriod')}
          nextLabel={t('statements.nextPeriod')}
        />
      </div>

      {/* Illustrative badge */}
      {data?.usesIllustrativeRules ? (
        <div>
          <Badge tone="amber">
            <TriangleAlert className="size-3" /> {t('statements.illustrative')}
          </Badge>
        </div>
      ) : null}

      {/* Error */}
      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">{t('statements.error')}</Card>
      ) : showEmpty ? (
        <StatementEmptyState title={t('statements.noData')} hint={t('statements.noDataHint')} />
      ) : (
        <div className="grid gap-[18px] lg:grid-cols-[1.5fr_1fr]">
          {/* LEFT — Revenue & Expense lines + Net profit highlight */}
          <Card className="p-2 px-7 pb-6">
            {/* Revenue section */}
            <div className="mb-1 mt-5 text-[11px] font-bold uppercase tracking-[0.06em] text-brand-700">
              {t('statements.revenue')}
            </div>

            {query.isLoading ? (
              <div className="my-4 flex justify-center text-brand-500">
                <Spinner />
              </div>
            ) : (
              <>
                {(data?.revenueLines ?? []).map((l) => (
                  <div
                    key={l.accountCode}
                    className="flex justify-between border-b border-line py-2.5"
                  >
                    <span className="text-sm text-ink-2">{l.accountCode}</span>
                    <span className="tnum font-mono text-sm text-ink">
                      {formatAmount(l.netMinor, currency, locale)}
                    </span>
                  </div>
                ))}

                {/* Total revenue */}
                <div className="flex justify-between border-b border-line-strong py-2.5">
                  <span className="text-sm font-semibold text-ink">
                    {t('statements.totalRevenue')}
                  </span>
                  <span className="tnum font-mono text-sm font-semibold text-ink">
                    {formatAmount(data?.totalRevenueMinor ?? 0, currency, locale)}
                  </span>
                </div>
              </>
            )}

            {/* Expense section */}
            <div className="mb-1 mt-5 text-[11px] font-bold uppercase tracking-[0.06em] text-loss">
              {t('statements.expense')}
            </div>

            {query.isLoading ? (
              <div className="my-4 flex justify-center text-brand-500">
                <Spinner />
              </div>
            ) : (
              <>
                {(data?.expenseLines ?? []).map((l) => (
                  <div
                    key={l.accountCode}
                    className="flex justify-between border-b border-line py-2.5"
                  >
                    <span className="text-sm text-ink-2">{l.accountCode}</span>
                    <span className="tnum font-mono text-sm text-ink">
                      {formatAmount(l.netMinor, currency, locale)}
                    </span>
                  </div>
                ))}

                {/* Total expense */}
                <div className="flex justify-between border-b border-line-strong py-2.5">
                  <span className="text-sm font-semibold text-ink">
                    {t('statements.totalExpense')}
                  </span>
                  <span className="tnum font-mono text-sm font-semibold text-ink">
                    {formatAmount(data?.totalExpenseMinor ?? 0, currency, locale)}
                  </span>
                </div>
              </>
            )}

            {/* Net profit / loss highlight box */}
            <div
              className={`mt-3.5 flex items-center justify-between rounded-[14px] px-4 py-4 ${
                profit ? 'bg-brand-50' : 'bg-tint-loss'
              }`}
            >
              <span className={`font-bold ${profit ? 'text-brand-800' : 'text-loss'}`}>
                {profit ? t('statements.netProfit') : t('statements.netLoss')}
              </span>
              {query.isLoading ? (
                <div className="h-7 w-32 animate-pulse rounded-lg bg-ink-100" />
              ) : (
                <span
                  className={`tnum font-mono text-[22px] font-extrabold ${
                    profit ? 'text-brand-700' : 'text-loss'
                  }`}
                >
                  {formatMoney(net, currency, locale)}
                </span>
              )}
            </div>
          </Card>

          {/* RIGHT — Expense composition */}
          <Card className="p-6">
            <div className="mb-1">
              <h2 className="font-display text-lg font-semibold text-ink">
                {t('statements.composition')}
              </h2>
              <p className="mt-0.5 text-xs text-ink-3">{t('statements.expense')}</p>
            </div>

            {query.isLoading ? (
              <div className="mt-6 flex justify-center text-brand-500">
                <Spinner />
              </div>
            ) : compositionSegments.length === 0 ? (
              <p className="mt-6 text-sm text-ink-3">{t('statements.noLines')}</p>
            ) : (
              <div className="mt-5 space-y-4">
                {/* Horizontal stacked bar */}
                <div className="flex h-3 overflow-hidden rounded-full">
                  {compositionSegments.map((seg) => (
                    <div
                      key={seg.accountCode}
                      style={{ width: `${seg.share * 100}%`, backgroundColor: seg.color }}
                    />
                  ))}
                </div>

                {/* Legend rows */}
                <div className="mt-3 space-y-0.5">
                  {compositionSegments.map((seg) => (
                    <div
                      key={seg.accountCode}
                      className="flex justify-between py-1.5"
                    >
                      <div className="flex items-center gap-2">
                        <span
                          className="size-2.5 shrink-0 rounded-[3px]"
                          style={{ backgroundColor: seg.color }}
                          aria-hidden
                        />
                        <span className="text-sm text-ink-2">{seg.accountCode}</span>
                      </div>
                      <span className="tnum font-mono text-sm text-ink">
                        {new Intl.NumberFormat(locale, {
                          style: 'percent',
                          minimumFractionDigits: 1,
                          maximumFractionDigits: 1,
                        }).format(seg.share)}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </Card>
        </div>
      )}
    </div>
  )
}
