import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { useIncomeStatement } from './api'
import {
  EMERALD,
  ROSE,
  KpiTile,
  LineSection,
  PeriodNav,
  StatementEmptyState,
  SummaryBars,
} from './parts'

/**
 * Income Statement (Laba Rugi) — a dashboard-style read of the period's P&L derived from the GL:
 * revenue / expense / net KPI tiles, a revenue-vs-expense bar, and an expandable per-account
 * breakdown. Owner/manager only (gated in the router + at the gateway). All amounts are integer
 * minor units rendered via the locale-aware money helpers (rules 8 & 9); a period with no GL entries
 * returns 204 → the empty state.
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
  const showEmpty = !query.isLoading && !query.isError && data == null

  return (
    <div className="space-y-7">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-3xl font-semibold tracking-tight text-ink">
            {t('statements.incomeTitle')}
          </h1>
          <p className="mt-1 text-sm text-ink-3">{t('statements.incomeSubtitle')}</p>
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

      {data?.usesIllustrativeRules ? (
        <Badge tone="amber">
          <TriangleAlert className="size-3" /> {t('statements.illustrative')}
        </Badge>
      ) : null}

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-rose">{t('statements.error')}</Card>
      ) : showEmpty ? (
        <StatementEmptyState title={t('statements.noData')} hint={t('statements.noDataHint')} />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <KpiTile
              label={t('statements.revenue')}
              minor={data?.totalRevenueMinor ?? 0}
              currency={currency}
              locale={locale}
              loading={query.isLoading}
            />
            <KpiTile
              label={t('statements.expense')}
              minor={data?.totalExpenseMinor ?? 0}
              currency={currency}
              locale={locale}
              loading={query.isLoading}
            />
            <KpiTile
              label={net >= 0 ? t('statements.netProfit') : t('statements.netLoss')}
              minor={net}
              currency={currency}
              locale={locale}
              loading={query.isLoading}
              tone={net >= 0 ? 'text-emerald-2' : 'text-rose'}
              emphatic
            />
          </div>

          <Card className="p-6">
            <h2 className="font-display text-lg font-semibold text-ink">
              {t('statements.breakdown')}
            </h2>
            <div className="mt-5 h-56">
              {query.isLoading || !data ? (
                <div className="grid h-full place-items-center text-ink-3">
                  <Spinner className="text-emerald" />
                </div>
              ) : (
                <SummaryBars
                  currency={currency}
                  data={[
                    { label: t('statements.revenue'), minor: data.totalRevenueMinor, fill: EMERALD },
                    { label: t('statements.expense'), minor: data.totalExpenseMinor, fill: ROSE },
                  ]}
                />
              )}
            </div>
          </Card>

          {data ? (
            <Card className="p-6">
              <details className="group">
                <summary className="flex cursor-pointer list-none items-center justify-between">
                  <h2 className="font-display text-lg font-semibold text-ink">
                    {t('statements.lineItems')}
                  </h2>
                  <ChevronDown className="size-4 text-ink-3 transition-transform group-open:rotate-180" />
                </summary>
                <div className="mt-5 space-y-6">
                  <LineSection
                    heading={t('statements.revenue')}
                    lines={data.revenueLines.map((l) => ({
                      accountCode: l.accountCode,
                      amountMinor: l.netMinor,
                    }))}
                    totalLabel={t('statements.totalRevenue')}
                    totalMinor={data.totalRevenueMinor}
                    currency={currency}
                    locale={locale}
                    emptyLabel={t('statements.noLines')}
                  />
                  <LineSection
                    heading={t('statements.expense')}
                    lines={data.expenseLines.map((l) => ({
                      accountCode: l.accountCode,
                      amountMinor: l.netMinor,
                    }))}
                    totalLabel={t('statements.totalExpense')}
                    totalMinor={data.totalExpenseMinor}
                    currency={currency}
                    locale={locale}
                    emptyLabel={t('statements.noLines')}
                  />
                </div>
              </details>
            </Card>
          ) : null}
        </>
      )}
    </div>
  )
}
