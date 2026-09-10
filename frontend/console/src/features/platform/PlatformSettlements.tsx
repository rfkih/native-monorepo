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
import { cn } from '@/lib/cn'
import {
  usePayoutSources,
  useSettlementHistory,
  useVoidSettlement,
  type PlatformSettlementRecord,
} from './platformApi'
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
        session={company}
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
  session,
  query,
  rows,
  channelCodes,
  channel,
  onChannelChange,
  locale,
}: {
  session: CompanySession
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
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr
                  key={row.id}
                  className={cn(
                    'border-b border-ink-50 last:border-0 hover:bg-hover',
                    // A taken-back payout is history, not money: it stays on the page (the books
                    // keep both entries) but must not read as a live figure.
                    row.voided && 'text-ink-3 line-through decoration-ink-300',
                  )}
                >
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
                  <td className="px-4 py-3 text-right">
                    {row.voided ? (
                      <span className="text-[12.5px] font-semibold text-ink-3 no-underline">
                        {t('platform.history.voided')}
                      </span>
                    ) : (
                      <VoidButton session={session} row={row} />
                    )}
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

/**
 * Takes one recorded payout back. Asks first — this posts a contra entry and hands the balance back
 * to the sub-ledger, which is a money movement, not an undo of a typo in a text field.
 *
 * <p>The row stays in the history afterwards: voiding stamps the settlement and posts a correction
 * rather than deleting it, so the books keep both.
 */
function VoidButton({
  session,
  row,
}: {
  session: CompanySession
  row: PlatformSettlementRecord
}) {
  const { t } = useTranslation()
  const [asking, setAsking] = useState(false)
  const voidIt = useVoidSettlement(session)

  if (!asking) {
    return (
      <button
        type="button"
        onClick={() => setAsking(true)}
        className="text-[13px] font-semibold text-emerald-2 underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
      >
        {t('platform.history.void')}
      </button>
    )
  }
  return (
    <span className="inline-flex items-center gap-2 whitespace-nowrap">
      <span className="text-[12.5px] text-ink-3">{t('platform.history.voidConfirm')}</span>
      {voidIt.isError ? (
        <span className="text-[12.5px] text-loss" role="alert">
          {t('platform.history.voidFailed')}
        </span>
      ) : null}
      <button
        type="button"
        disabled={voidIt.isPending}
        onClick={() => voidIt.mutate(row.id, { onSuccess: () => setAsking(false) })}
        className="text-[13px] font-semibold text-loss underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
      >
        {t('platform.history.voidYes')}
      </button>
      <button
        type="button"
        onClick={() => setAsking(false)}
        className="text-[13px] text-ink-3 underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
      >
        {t('common.cancel')}
      </button>
    </span>
  )
}
