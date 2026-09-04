/**
 * Marketplace / Platform Online — a per-period report joining THREE sources into one row per
 * delivery/marketplace channel (GoFood/GrabFood/ShopeeFood-style — see `features/channels/`):
 * ONLINE sales rung this period (restaurant-service), settlements recorded this period
 * (finance-service), and the channel's CURRENT outstanding balance (finance-service, reused as-is
 * from `features/platform/platformApi.ts`'s `useOutstanding` — never re-fetched here). The join
 * itself is the pure, unit-tested `mergeMarketplaceRows` (see `mergeRows.ts`).
 *
 * Owner/accountant only — routed/gated exactly like `/platform-settlements` (App.tsx
 * `financeAllowed`), `channels` feature tier (BASIC, `lib/featureTier.ts`).
 *
 * Strings rule (rule 9): every label is an i18n key, en+id. Money rule 8: minor units in, formatted
 * via `formatMoney` (Intl, locale-aware). Outstanding may be NEGATIVE (a clawback) and renders in
 * the loss color, never clamped — mirrors `PlatformSettlements.tsx`'s outstanding table exactly.
 */
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { EmptyState, PeriodNav } from '@/features/_shared/financeUi'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { cn } from '@/lib/cn'
import { useSalesChannels } from '@/features/channels/channelsApi'
import { useOutstanding } from '@/features/platform/platformApi'
import { useChannelSalesSummary, useChannelSettlementSummary } from './marketplaceApi'
import { mergeMarketplaceRows, totalMarketplaceRow, type MarketplaceRow } from './mergeRows'

export function Marketplace() {
  const { t } = useTranslation()
  const { company } = useSession()
  if (!company) {
    return <EmptyState title={t('marketplace.noCompany')} hint={t('marketplace.noCompanyHint')} />
  }
  return <MarketplaceInner company={company} />
}

function MarketplaceInner({ company }: { company: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)
  const [period, setPeriod] = useState(currentPeriod())

  const salesQuery = useChannelSalesSummary(company, period)
  const settlementsQuery = useChannelSettlementSummary(company, period)
  const outstandingQuery = useOutstanding(company)
  // The channel catalog (features/channels/) supplies the display NAME for a channelCode — every
  // channel, active and inactive, so a deactivated-but-historically-active channel still shows a
  // name rather than falling back to its bare code.
  const channelsQuery = useSalesChannels(company)

  const channelNames = useMemo(
    () => new Map((channelsQuery.data ?? []).map((c) => [c.code, c.name])),
    [channelsQuery.data],
  )

  const rows = useMemo(
    () =>
      mergeMarketplaceRows(
        salesQuery.data ?? [],
        settlementsQuery.data ?? [],
        outstandingQuery.data ?? [],
        company.baseCurrency,
      ),
    [salesQuery.data, settlementsQuery.data, outstandingQuery.data, company.baseCurrency],
  )

  const totals = useMemo(
    () => totalMarketplaceRow(rows, company.baseCurrency),
    [rows, company.baseCurrency],
  )

  const isError = salesQuery.isError || settlementsQuery.isError || outstandingQuery.isError
  // Initial load only (no data on screen yet) — a period step keeps the prior month's rows visible
  // (marketplaceApi.ts's `keepPreviousData`) instead of flashing back to a skeleton.
  const isLoading =
    (salesQuery.isLoading || settlementsQuery.isLoading || outstandingQuery.isLoading) &&
    rows.length === 0

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('marketplace.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('marketplace.subtitle')}</p>
        </div>
        <PeriodNav
          period={period}
          locale={locale}
          onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
          onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
          prevLabel={t('marketplace.prevPeriod')}
          nextLabel={t('marketplace.nextPeriod')}
        />
      </div>

      {isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('marketplace.error')}
        </Card>
      ) : isLoading ? (
        <ListSkeleton rows={4} />
      ) : rows.length === 0 ? (
        <EmptyState title={t('marketplace.empty')} hint={t('marketplace.emptyHint')} />
      ) : (
        <MarketplaceTable rows={rows} totals={totals} channelNames={channelNames} locale={locale} />
      )}
    </div>
  )
}

function MarketplaceTable({
  rows,
  totals,
  channelNames,
  locale,
}: {
  rows: MarketplaceRow[]
  totals: ReturnType<typeof totalMarketplaceRow>
  channelNames: Map<string, string>
  locale: string
}) {
  const { t } = useTranslation()

  return (
    <Card className="overflow-hidden rounded-[20px]">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
            <th className="px-4 py-3">{t('marketplace.colPlatform')}</th>
            <th className="px-4 py-3 text-right">{t('marketplace.colGross')}</th>
            <th className="px-4 py-3 text-right">{t('marketplace.colFee')}</th>
            <th className="px-4 py-3 text-right">{t('marketplace.colNet')}</th>
            <th className="px-4 py-3 text-right">{t('marketplace.colOutstanding')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.channelCode}
              className="border-b border-ink-50 last:border-0 hover:bg-hover"
            >
              <td className="px-4 py-3">
                <div className="font-medium text-ink">
                  {channelNames.get(row.channelCode) ?? row.channelCode}
                </div>
                <div className="font-mono text-xs text-ink-3">{row.channelCode}</div>
              </td>
              <td className="tnum px-4 py-3 text-right font-mono text-ink">
                {formatMoney(row.grossSalesMinor, row.currency, locale)}
              </td>
              <td className="tnum px-4 py-3 text-right font-mono text-ink">
                {formatMoney(row.feeMinor, row.currency, locale)}
              </td>
              <td className="tnum px-4 py-3 text-right font-mono text-ink">
                {formatMoney(row.netSettledMinor, row.currency, locale)}
              </td>
              <td
                className={cn(
                  'tnum px-4 py-3 text-right font-mono font-semibold',
                  row.outstandingMinor < 0 ? 'text-loss' : 'text-ink',
                )}
              >
                {formatMoney(row.outstandingMinor, row.currency, locale)}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t-[1.5px] border-line-strong bg-paper">
            <th scope="row" className="px-4 py-3 text-left text-sm font-semibold text-ink">
              {t('marketplace.totalsLabel')}
            </th>
            <td className="tnum px-4 py-3 text-right font-mono text-sm font-semibold text-ink">
              {formatMoney(totals.grossSalesMinor, totals.currency, locale)}
            </td>
            <td className="tnum px-4 py-3 text-right font-mono text-sm font-semibold text-ink">
              {formatMoney(totals.feeMinor, totals.currency, locale)}
            </td>
            <td className="tnum px-4 py-3 text-right font-mono text-sm font-semibold text-ink">
              {formatMoney(totals.netSettledMinor, totals.currency, locale)}
            </td>
            <td
              className={cn(
                'tnum px-4 py-3 text-right font-mono text-sm font-semibold',
                totals.outstandingMinor < 0 ? 'text-loss' : 'text-ink',
              )}
            >
              {formatMoney(totals.outstandingMinor, totals.currency, locale)}
            </td>
          </tr>
        </tfoot>
      </table>
    </Card>
  )
}
