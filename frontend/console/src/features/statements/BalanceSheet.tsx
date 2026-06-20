import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, ChevronDown, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { useBalanceSheet, type BalanceLine } from './api'
import {
  EMERALD,
  GOLD,
  SLATE,
  KpiTile,
  LineSection,
  PeriodNav,
  StatementEmptyState,
  SummaryBars,
} from './parts'

/** The synthetic retained-earnings account code finance-service appends to every balance sheet. */
const RETAINED_EARNINGS_ACCOUNT = '3000-RETAINED-EARNINGS'

/**
 * Balance Sheet (Laporan Posisi Keuangan) — a dashboard-style read of the cumulative position as of
 * the selected period end, derived from the GL: assets / liabilities / equity KPI tiles, a
 * composition bar, a "balanced" check (assets == liabilities + equity, guaranteed by the service),
 * and an expandable per-account breakdown. Owner/manager only. Integer minor units throughout
 * (rules 8 & 9); no GL entries → 204 → the empty state.
 */
export function BalanceSheet() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)

  const [asOf, setAsOf] = useState(currentPeriod())

  const query = useBalanceSheet({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    asOf,
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
  const showEmpty = !query.isLoading && !query.isError && data == null

  // Localize the synthetic retained-earnings line; pass other accounts through by code.
  const equityLine = (l: BalanceLine) => ({
    accountCode: l.accountCode,
    label: l.accountCode === RETAINED_EARNINGS_ACCOUNT ? t('statements.retainedEarnings') : undefined,
    amountMinor: l.balanceMinor,
  })

  return (
    <div className="space-y-7">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-3xl font-semibold tracking-tight text-ink">
            {t('statements.balanceTitle')}
          </h1>
          <p className="mt-1 text-sm text-ink-3">{t('statements.balanceSubtitle')}</p>
        </div>
        <PeriodNav
          period={asOf}
          locale={locale}
          onPrev={() => setAsOf((p) => shiftPeriod(p, -1))}
          onNext={() => setAsOf((p) => shiftPeriod(p, 1))}
          prevLabel={t('statements.prevPeriod')}
          nextLabel={t('statements.nextPeriod')}
        />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        {data?.usesIllustrativeRules ? (
          <Badge tone="amber">
            <TriangleAlert className="size-3" /> {t('statements.illustrative')}
          </Badge>
        ) : null}
        {data ? (
          <Badge tone="emerald">
            <Check className="size-3" /> {t('statements.balanced')}
          </Badge>
        ) : null}
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-rose">{t('statements.error')}</Card>
      ) : showEmpty ? (
        <StatementEmptyState title={t('statements.noData')} hint={t('statements.noDataHint')} />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <KpiTile
              label={t('statements.totalAssets')}
              minor={data?.totalAssetsMinor ?? 0}
              currency={currency}
              locale={locale}
              loading={query.isLoading}
              emphatic
            />
            <KpiTile
              label={t('statements.totalLiabilities')}
              minor={data?.totalLiabilitiesMinor ?? 0}
              currency={currency}
              locale={locale}
              loading={query.isLoading}
            />
            <KpiTile
              label={t('statements.totalEquity')}
              minor={data?.totalEquityMinor ?? 0}
              currency={currency}
              locale={locale}
              loading={query.isLoading}
            />
          </div>

          <Card className="p-6">
            <h2 className="font-display text-lg font-semibold text-ink">
              {t('statements.composition')}
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
                    { label: t('statements.assets'), minor: data.totalAssetsMinor, fill: EMERALD },
                    {
                      label: t('statements.liabilities'),
                      minor: data.totalLiabilitiesMinor,
                      fill: SLATE,
                    },
                    { label: t('statements.equity'), minor: data.totalEquityMinor, fill: GOLD },
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
                    heading={t('statements.assets')}
                    lines={data.assetLines.map((l) => ({
                      accountCode: l.accountCode,
                      amountMinor: l.balanceMinor,
                    }))}
                    totalLabel={t('statements.totalAssets')}
                    totalMinor={data.totalAssetsMinor}
                    currency={currency}
                    locale={locale}
                    emptyLabel={t('statements.noLines')}
                  />
                  <LineSection
                    heading={t('statements.liabilities')}
                    lines={data.liabilityLines.map((l) => ({
                      accountCode: l.accountCode,
                      amountMinor: l.balanceMinor,
                    }))}
                    totalLabel={t('statements.totalLiabilities')}
                    totalMinor={data.totalLiabilitiesMinor}
                    currency={currency}
                    locale={locale}
                    emptyLabel={t('statements.noLines')}
                  />
                  <LineSection
                    heading={t('statements.equity')}
                    lines={data.equityLines.map(equityLine)}
                    totalLabel={t('statements.totalEquity')}
                    totalMinor={data.totalEquityMinor}
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
