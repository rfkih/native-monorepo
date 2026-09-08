/**
 * PlatformSettlements — record what a PAYER paid out and track what each still owes (ADR 0036
 * Phase C2, reshaped by ADR 0076). Three sections: the payout form (payers, the sources each one
 * settles, and one net figure read off the bank statement), who pays out the QRIS balance, and a
 * filterable history table. Owner/accountant only — gated at the gateway with the rest of the
 * detailed books.
 *
 * <p>Grouped by payer rather than by channel because that is how the money arrives: one Shopee
 * transfer covers both its ShopeeFood orders and its counter QRIS, so it is entered once.
 *
 * Strings rule (rule 9): every label is an i18n key, en+id. Money rule 8: minor units in, formatted
 * via `formatMoney` (Intl, locale-aware) for display and parsed exponent-aware for input.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Select } from '@/components/ui/Select'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { usePayoutSources, useSettlementHistory, type PlatformSettlementRecord } from './platformApi'
import { PayoutSection } from './PayoutSection'
import { SettlementSourceSettings } from './SettlementSourceSettings'

export function PlatformSettlements() {
  const { t } = useTranslation()
  const { company } = useSession()
  if (!company) {
    return <EmptyState title={t('platform.noCompany')} hint={t('platform.noCompanyHint')} />
  }
  return <PlatformSettlementsInner company={company} />
}

function PlatformSettlementsInner({ company }: { company: CompanySession }) {
  const { t, i18n } = useTranslation()
  const locale = localeOf(i18n.language)

  const sourcesQuery = usePayoutSources(company)
  const sources = sourcesQuery.data ?? []

  const [historyChannel, setHistoryChannel] = useState('')
  const historyQuery = useSettlementHistory(company, historyChannel || undefined)
  const history = historyQuery.data ?? []

  return (
    <div className="flex flex-col gap-[18px]">
      <div>
        <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
          {t('platform.title')}
        </h1>
        <p className="mt-1.5 text-sm text-ink-3">{t('platform.subtitle')}</p>
      </div>

      <PayoutSection session={company} locale={locale} />

      <SettlementSourceSettings session={company} />

      <HistorySection
        query={historyQuery}
        rows={history}
        // Union with the codes the history itself carries: `sources` only lists payers that
        // still owe something, so a payer would vanish from its own history filter the moment it
        // was cleared to zero — which is exactly what this page exists to do.
        channelCodes={Array.from(
          new Set([...sources.map((s) => s.sourceCode), ...history.map((h) => h.channelCode)]),
        ).sort()}
        channel={historyChannel}
        onChannelChange={setHistoryChannel}
        locale={locale}
      />
    </div>
  )
}

function HistorySection({
  query,
  rows,
  channelCodes,
  channel,
  onChannelChange,
  locale,
}: {
  query: ReturnType<typeof useSettlementHistory>
  rows: PlatformSettlementRecord[]
  channelCodes: string[]
  channel: string
  onChannelChange: (value: string) => void
  locale: string
}) {
  const { t } = useTranslation()

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-display text-lg font-semibold text-ink">{t('platform.history.title')}</h2>
        <Select
          aria-label={t('platform.history.filterAll')}
          value={channel}
          onChange={(e) => onChannelChange(e.target.value)}
          className="w-auto min-w-[180px]"
        >
          <option value="">{t('platform.history.filterAll')}</option>
          {channelCodes.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </Select>
      </div>

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('platform.history.error')}
        </Card>
      ) : query.isLoading ? (
        <ListSkeleton rows={6} />
      ) : rows.length === 0 ? (
        <EmptyState title={t('platform.history.empty')} hint={t('platform.history.emptyHint')} />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('platform.history.colChannel')}</th>
                <th className="px-4 py-3">{t('platform.history.colSettledAt')}</th>
                <th className="px-4 py-3 text-right">{t('platform.history.colGross')}</th>
                <th className="px-4 py-3 text-right">{t('platform.history.colNet')}</th>
                <th className="px-4 py-3 text-right">{t('platform.history.colFee')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3 font-mono text-ink-2">{row.channelCode}</td>
                  <td className="px-4 py-3 text-ink-3">{formatSettledAt(row.settledAt, locale)}</td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink">
                    {formatMoney(row.grossMinor, row.currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink">
                    {formatMoney(row.netMinor, row.currency, locale)}
                  </td>
                  <td className="tnum px-4 py-3 text-right font-mono text-ink">
                    {formatMoney(row.feeMinor, row.currency, locale)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </section>
  )
}

/** Localized date + time (settledAt is a full Instant, not a bare date) — falls back to the raw ISO. */
function formatSettledAt(iso: string, locale: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}
