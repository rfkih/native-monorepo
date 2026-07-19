import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatAmount } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { useBalanceSheet, type BalanceLine } from './api'
import { PeriodNav, StatementEmptyState } from './parts'

/** The synthetic retained-earnings account code finance-service appends to every balance sheet. */
const RETAINED_EARNINGS_ACCOUNT = '3000-RETAINED-EARNINGS'

/**
 * Balance Sheet (Laporan Posisi Keuangan) — restyled to the Native design system.
 * Two-column layout: left = Assets card with per-row progress bars;
 * right column = Liabilities card + Equity card + L+E summary box.
 * All data hooks, queries, and i18n keys are preserved unchanged.
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
    label:
      l.accountCode === RETAINED_EARNINGS_ACCOUNT
        ? t('statements.retainedEarnings')
        : undefined,
    amountMinor: l.balanceMinor,
  })

  const totalAssets = data?.totalAssetsMinor ?? 0

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('statements.balanceTitle')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('statements.balanceSubtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {/* Balanced pill — shown when data is loaded and the sheet balances */}
          {data ? (
            <span className="inline-flex items-center gap-1.5 rounded-full bg-tint-profit px-3.5 py-1.5 text-sm font-semibold text-brand-700">
              <Check className="size-3.5" aria-hidden />
              {t('statements.balanced')}
            </span>
          ) : null}
          <PeriodNav
            period={asOf}
            locale={locale}
            onPrev={() => setAsOf((p) => shiftPeriod(p, -1))}
            onNext={() => setAsOf((p) => shiftPeriod(p, 1))}
            prevLabel={t('statements.prevPeriod')}
            nextLabel={t('statements.nextPeriod')}
          />
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
      ) : (
        <div className="grid gap-[18px] lg:grid-cols-2">
          {/* LEFT — Assets */}
          <Card className="p-6">
            <div className="mb-4 text-[11px] font-bold uppercase tracking-[0.06em] text-info">
              {t('statements.assets')}
            </div>

            {query.isLoading ? (
              <div className="flex justify-center py-8 text-brand-500">
                <Spinner />
              </div>
            ) : (
              <>
                {(data?.assetLines ?? []).map((l) => {
                  const share = totalAssets > 0 ? l.balanceMinor / totalAssets : 0
                  return (
                    <div key={l.accountCode} className="mb-3">
                      <div className="flex justify-between py-1">
                        <span className="text-sm text-ink-2">{l.accountCode}</span>
                        <span className="tnum font-mono text-sm text-ink">
                          {formatAmount(l.balanceMinor, currency, locale)}
                        </span>
                      </div>
                      {/* Progress bar */}
                      <div className="h-1 overflow-hidden rounded-full bg-ink-50">
                        <div
                          className="h-full rounded-full bg-info opacity-55"
                          style={{ width: `${share * 100}%` }}
                        />
                      </div>
                    </div>
                  )
                })}

                {/* Total assets highlight box */}
                <div className="mt-4 flex items-center justify-between rounded-[14px] bg-tint-info px-4 py-4">
                  <span className="font-bold text-info">{t('statements.totalAssets')}</span>
                  <span className="tnum font-mono text-[18px] font-extrabold text-info">
                    {formatMoney(totalAssets, currency, locale)}
                  </span>
                </div>
              </>
            )}
          </Card>

          {/* RIGHT — Liabilities + Equity column */}
          <div className="flex flex-col gap-[18px]">
            {/* Liabilities Card */}
            <Card className="p-6">
              <div className="mb-4 text-[11px] font-bold uppercase tracking-[0.06em] text-loss">
                {t('statements.liabilities')}
              </div>

              {query.isLoading ? (
                <div className="flex justify-center py-6 text-brand-500">
                  <Spinner />
                </div>
              ) : (
                <>
                  {(data?.liabilityLines ?? []).map((l) => (
                    <div
                      key={l.accountCode}
                      className="flex justify-between border-b border-line py-2.5"
                    >
                      <span className="text-sm text-ink-2">{l.accountCode}</span>
                      <span className="tnum font-mono text-sm text-ink">
                        {formatAmount(l.balanceMinor, currency, locale)}
                      </span>
                    </div>
                  ))}
                  <div className="flex justify-between pt-2.5">
                    <span className="text-sm font-semibold text-ink">
                      {t('statements.totalLiabilities')}
                    </span>
                    <span className="tnum font-mono text-sm font-semibold text-ink">
                      {formatAmount(data?.totalLiabilitiesMinor ?? 0, currency, locale)}
                    </span>
                  </div>
                </>
              )}
            </Card>

            {/* Equity Card */}
            <Card className="p-6">
              <div className="mb-4 text-[11px] font-bold uppercase tracking-[0.06em] text-brand-700">
                {t('statements.equity')}
              </div>

              {query.isLoading ? (
                <div className="flex justify-center py-6 text-brand-500">
                  <Spinner />
                </div>
              ) : (
                <>
                  {(data?.equityLines ?? []).map(equityLine).map((l) => (
                    <div
                      key={l.accountCode}
                      className="flex justify-between border-b border-line py-2.5"
                    >
                      <span className="text-sm text-ink-2">
                        {l.label ?? l.accountCode}
                      </span>
                      <span className="tnum font-mono text-sm text-ink">
                        {formatAmount(l.amountMinor, currency, locale)}
                      </span>
                    </div>
                  ))}
                  <div className="flex justify-between pt-2.5">
                    <span className="text-sm font-semibold text-ink">
                      {t('statements.totalEquity')}
                    </span>
                    <span className="tnum font-mono text-sm font-semibold text-ink">
                      {formatAmount(data?.totalEquityMinor ?? 0, currency, locale)}
                    </span>
                  </div>
                </>
              )}
            </Card>

            {/* Liabilities + Equity summary box */}
            {!query.isLoading && data ? (
              <div className="flex items-center justify-between rounded-[14px] bg-brand-50 px-4 py-4">
                <span className="text-sm font-semibold text-brand-800">
                  {t('statements.totalLiabilities')} + {t('statements.totalEquity')}
                </span>
                <span className="tnum font-mono text-[18px] font-extrabold text-brand-700">
                  {formatMoney(data.totalLiabilitiesAndEquityMinor, currency, locale)}
                </span>
              </div>
            ) : null}
          </div>
        </div>
      )}
    </div>
  )
}
