import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Download, Printer, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ListSkeleton, Skeleton, StatCardsSkeleton } from '@/components/ui/Skeleton'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatAmount, formatPercent } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { useBalanceSheet, type BalanceLine } from './api'
import { downloadCsv } from '@/lib/csv'
import { EntityScope, LineSection, PeriodNav, StatementEmptyState, SummaryCard } from './parts'

/** The synthetic retained-earnings account code finance-service appends to every balance sheet. */
const RETAINED_EARNINGS_ACCOUNT = '3000-RETAINED-EARNINGS'

/**
 * Balance Sheet (Laporan Posisi Keuangan) — design 2c: the balance check leads, because
 * that's the question being asked. Liabilities take an ink tone rather than red — they
 * aren't a loss — and the funding bar replaces the old composition chart.
 * All data hooks, queries, and existing i18n keys are preserved unchanged.
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
  const totalLiabilities = data?.totalLiabilitiesMinor ?? 0
  const totalEquity = data?.totalEquityMinor ?? 0
  const delta = totalAssets - (data?.totalLiabilitiesAndEquityMinor ?? 0)
  const balanced = delta === 0
  const funded = totalLiabilities + totalEquity
  const liabilityShare = funded > 0 ? totalLiabilities / funded : 0
  const equityShare = funded > 0 ? totalEquity / funded : 0

  const exportCsv = () => {
    if (!data) return
    downloadCsv(`balance-sheet-${asOf}.csv`, [
      [company.name, t('statements.scopeAllUnits')],
      [t('statements.balanceTitle'), asOf, currency],
      [],
      [t('statements.assets')],
      ...data.assetLines.map((l) => [l.accountCode, l.balanceMinor]),
      [t('statements.totalAssets'), data.totalAssetsMinor],
      [],
      [t('statements.liabilities')],
      ...data.liabilityLines.map((l) => [l.accountCode, l.balanceMinor]),
      [t('statements.totalLiabilities'), data.totalLiabilitiesMinor],
      [],
      [t('statements.equity')],
      ...data.equityLines.map((l) => [l.accountCode, l.balanceMinor]),
      [t('statements.totalEquity'), data.totalEquityMinor],
    ])
  }

  return (
    <div className="flex flex-col gap-5">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <EntityScope name={company.name} scope={t('statements.scopeAllUnits')} />
          <h1 className="font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
            {t('statements.balanceTitle')}
          </h1>
          <p className="mt-1.5 text-[15px] text-ink-3">{t('statements.balanceSubtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2.5 print:hidden">
          <PeriodNav
            period={asOf}
            locale={locale}
            onPrev={() => setAsOf((p) => shiftPeriod(p, -1))}
            onNext={() => setAsOf((p) => shiftPeriod(p, 1))}
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
        <>
          <Skeleton className="h-16 rounded-[20px]" />
          <StatCardsSkeleton cards={3} />
          <Skeleton className="h-24 rounded-card" />
          <div className="grid gap-5 lg:grid-cols-2">
            <ListSkeleton rows={5} className="rounded-[20px]" />
            <ListSkeleton rows={5} className="rounded-[20px]" />
          </div>
        </>
      ) : (
        <>
          {/* Trust framing — the first thing on the page answers the only question anyone
              opens a balance sheet to check. */}
          <div
            className={`flex items-center gap-3.5 rounded-[20px] border px-5 py-[18px] ${
              balanced
                ? 'border-profit/25 bg-tint-profit'
                : 'border-warning/30 bg-tint-warning'
            }`}
          >
            <span
              className={`grid size-8 shrink-0 place-items-center rounded-full ${
                balanced ? 'bg-profit' : 'bg-warning'
              }`}
            >
              {balanced ? (
                <Check className="size-[17px] text-white" strokeWidth={3} aria-hidden />
              ) : (
                <TriangleAlert className="size-4 text-white" aria-hidden />
              )}
            </span>
            <div className="min-w-0 flex-1">
              <div className="text-[15px] font-bold text-ink">
                {balanced ? t('statements.balancedTitle') : t('statements.unbalancedTitle')}
              </div>
              <div className="mt-0.5 text-[13px] text-ink-2">
                {balanced ? t('statements.balancedBody') : t('statements.unbalancedBody')}
              </div>
            </div>
            <span
              className={`tnum shrink-0 font-mono text-[15px] font-bold ${
                balanced ? 'text-profit-ink' : 'text-amber-2'
              }`}
            >
              Δ {formatMoney(delta, currency, locale)}
            </span>
          </div>

          {/* Summary cards — liabilities in ink: they aren't a loss */}
          <div className="grid gap-4 sm:grid-cols-3 print:grid-cols-3 print:gap-3">
            <SummaryCard
              chipClass="bg-emerald"
              label={t('statements.assets')}
              value={formatMoney(totalAssets, currency, locale)}
            />
            <SummaryCard
              chipClass="bg-ink-300"
              label={t('statements.liabilities')}
              value={formatMoney(totalLiabilities, currency, locale)}
            />
            <SummaryCard
              chipClass="bg-profit"
              label={t('statements.equity')}
              value={formatMoney(totalEquity, currency, locale)}
            />
          </div>

          {/* Funding bar */}
          <Card className="p-6">
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <h2 className="font-display text-[15px] font-semibold text-ink">
                {t('statements.funding')}
              </h2>
              <span className="text-[13px] text-ink-3">
                {t('statements.fundingSplit', {
                  liabilities: formatPercent(liabilityShare, locale),
                  equity: formatPercent(equityShare, locale),
                })}
              </span>
            </div>
            <div className="flex h-3.5 overflow-hidden rounded-full bg-ink-50">
              <div className="bg-ink-300" style={{ width: `${liabilityShare * 100}%` }} />
              <div className="bg-profit" style={{ width: `${equityShare * 100}%` }} />
            </div>
          </Card>

          {/* Account tables */}
          <div className="grid gap-5 lg:grid-cols-2">
            <Card className="p-6">
              <LineSection
                heading={t('statements.assets')}
                lines={(data?.assetLines ?? []).map((l) => ({
                  accountCode: l.accountCode,
                  amountMinor: l.balanceMinor,
                }))}
                totalLabel={t('statements.totalAssets')}
                totalMinor={totalAssets}
                currency={currency}
                locale={locale}
                emptyLabel={t('statements.noLines')}
                format={formatAmount}
              />
            </Card>
            <Card className="p-6">
              <LineSection
                heading={t('statements.liabilities')}
                lines={(data?.liabilityLines ?? []).map((l) => ({
                  accountCode: l.accountCode,
                  amountMinor: l.balanceMinor,
                }))}
                totalLabel={t('statements.totalLiabilities')}
                totalMinor={totalLiabilities}
                currency={currency}
                locale={locale}
                emptyLabel={t('statements.noLines')}
                format={formatAmount}
              />
              <div className="mt-5">
                <LineSection
                  heading={t('statements.equity')}
                  lines={(data?.equityLines ?? []).map(equityLine)}
                  totalLabel={t('statements.totalEquity')}
                  totalMinor={totalEquity}
                  currency={currency}
                  locale={locale}
                  emptyLabel={t('statements.noLines')}
                  format={formatAmount}
                />
              </div>
            </Card>
          </div>
        </>
      )}
    </div>
  )
}
