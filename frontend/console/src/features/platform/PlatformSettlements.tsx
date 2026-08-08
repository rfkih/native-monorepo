/**
 * PlatformSettlements — record a payout from a delivery/marketplace channel (GoFood/GrabFood-style)
 * and track what each channel still owes (ADR 0036 Phase C2). Three sections: outstanding-per-
 * channel (a channel's balance may be NEGATIVE — a refund clawback after a full settlement — and
 * renders in the loss color, never clamped), a settle form (channel picker sourced from the
 * outstanding rows; gross/net in MAJOR units, fee DERIVED read-only), and a filterable history
 * table. Owner/manager only — routed/gated exactly like /channels (App.tsx `canDashboard`), a
 * company-wide back-office surface, not outlet-scoped.
 *
 * The form does not post directly: submit opens {@link ConfirmSettleDialog}, which mints the
 * Idempotency-Key and is the only place that actually calls the settle mutation (see its docs).
 *
 * Strings rule (rule 9): every label is an i18n key, en+id. Money rule 8: minor units in, formatted
 * via `formatMoney` (Intl, locale-aware) for display and parsed exponent-aware for input.
 */
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { Select } from '@/components/ui/Select'
import { EmptyState } from '@/features/_shared/financeUi'
import { useSession, type CompanySession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, isoMinorExponent } from '@/lib/money'
import { cn } from '@/lib/cn'
import { deriveFeeMinor, parsePlatformAmountInput } from './amount'
import {
  useOutstanding,
  useSettlementHistory,
  type PlatformOutstanding,
  type PlatformSettlementRecord,
} from './platformApi'
import { ConfirmSettleDialog, type PendingSettlement } from './ConfirmSettleDialog'

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

  const outstandingQuery = useOutstanding(company)
  const outstanding = outstandingQuery.data ?? []
  const outstandingReady = !outstandingQuery.isLoading && !outstandingQuery.isError

  const [historyChannel, setHistoryChannel] = useState('')
  const historyQuery = useSettlementHistory(company, historyChannel || undefined)
  const history = historyQuery.data ?? []

  const [pending, setPending] = useState<PendingSettlement | null>(null)

  return (
    <div className="flex flex-col gap-[18px]">
      <div>
        <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
          {t('platform.title')}
        </h1>
        <p className="mt-1.5 text-sm text-ink-3">{t('platform.subtitle')}</p>
      </div>

      <OutstandingSection query={outstandingQuery} rows={outstanding} locale={locale} />

      {outstandingReady ? (
        <SettleForm
          session={company}
          outstanding={outstanding}
          locale={locale}
          onReview={setPending}
        />
      ) : null}

      <HistorySection
        query={historyQuery}
        rows={history}
        channelCodes={channelCodesOf(outstanding)}
        channel={historyChannel}
        onChannelChange={setHistoryChannel}
        locale={locale}
      />

      {pending ? (
        <ConfirmSettleDialog
          session={company}
          pending={pending}
          onClose={() => setPending(null)}
          onSettled={() => setPending(null)}
        />
      ) : null}
    </div>
  )
}

function channelCodesOf(rows: PlatformOutstanding[]): string[] {
  return Array.from(new Set(rows.map((r) => r.channelCode))).sort()
}

function rowKey(row: PlatformOutstanding): string {
  return `${row.channelCode}::${row.currency}`
}

function OutstandingSection({
  query,
  rows,
  locale,
}: {
  query: ReturnType<typeof useOutstanding>
  rows: PlatformOutstanding[]
  locale: string
}) {
  const { t } = useTranslation()
  return (
    <section className="flex flex-col gap-3">
      <h2 className="font-display text-lg font-semibold text-ink">{t('platform.outstanding.title')}</h2>
      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('platform.outstanding.error')}
        </Card>
      ) : query.isLoading ? (
        <ListSkeleton rows={4} />
      ) : rows.length === 0 ? (
        <EmptyState
          title={t('platform.outstanding.empty')}
          hint={t('platform.outstanding.emptyHint')}
        />
      ) : (
        <Card className="overflow-hidden rounded-[20px]">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                <th className="px-4 py-3">{t('platform.outstanding.colChannel')}</th>
                <th className="px-4 py-3">{t('platform.outstanding.colCurrency')}</th>
                <th className="px-4 py-3 text-right">{t('platform.outstanding.colOutstanding')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={rowKey(row)} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                  <td className="px-4 py-3 font-mono text-ink-2">{row.channelCode}</td>
                  <td className="px-4 py-3 text-ink-3">{row.currency}</td>
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
          </table>
        </Card>
      )}
    </section>
  )
}

function SettleForm({
  session,
  outstanding,
  locale,
  onReview,
}: {
  session: CompanySession
  outstanding: PlatformOutstanding[]
  locale: string
  onReview: (pending: PendingSettlement) => void
}) {
  const { t } = useTranslation()

  const [selectedKey, setSelectedKey] = useState('')
  const [grossMajor, setGrossMajor] = useState('')
  const [netMajor, setNetMajor] = useState('')

  const selected = outstanding.find((row) => rowKey(row) === selectedKey) ?? null
  const currency = selected?.currency ?? session.baseCurrency
  const exponent = isoMinorExponent(currency)
  const step = exponent === 0 ? '1' : (1 / 10 ** exponent).toString()

  const grossMinor = selected ? parsePlatformAmountInput(grossMajor, currency) : null
  const netMinor = selected ? parsePlatformAmountInput(netMajor, currency, { allowZero: true }) : null
  const feeMinor = deriveFeeMinor(grossMinor, netMinor)
  const netExceedsGross = grossMinor != null && netMinor != null && netMinor > grossMinor

  const canSubmit = selected != null && grossMinor != null && netMinor != null && !netExceedsGross

  function selectChannel(key: string) {
    setSelectedKey(key)
    setGrossMajor('')
    setNetMajor('')
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!canSubmit || !selected || grossMinor == null || netMinor == null || feeMinor == null) return
    onReview({
      channelCode: selected.channelCode,
      currency: selected.currency,
      grossMinor,
      netMinor,
      feeMinor,
    })
  }

  if (outstanding.length === 0) {
    return (
      <Card className="p-6">
        <h2 className="font-display text-lg font-semibold text-ink">{t('platform.form.title')}</h2>
        <p className="mt-2 text-sm text-ink-3">{t('platform.form.noChannels')}</p>
      </Card>
    )
  }

  return (
    <Card className="p-6">
      <form onSubmit={submit} className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">{t('platform.form.title')}</h2>

        <Field label={t('platform.form.channelLabel')} htmlFor="settle-channel">
          <Select
            id="settle-channel"
            value={selectedKey}
            onChange={(e) => selectChannel(e.target.value)}
          >
            <option value="">{t('platform.form.channelPlaceholder')}</option>
            {outstanding.map((row) => (
              <option key={rowKey(row)} value={rowKey(row)}>
                {row.channelCode} · {row.currency}
              </option>
            ))}
          </Select>
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label={t('platform.form.grossLabel', { currency })}
            htmlFor="settle-gross"
            hint={t('platform.form.grossHint')}
          >
            <TextInput
              id="settle-gross"
              type="number"
              min="0"
              step={step}
              value={grossMajor}
              disabled={!selected}
              onChange={(e) => setGrossMajor(e.target.value)}
            />
          </Field>
          <Field
            label={t('platform.form.netLabel', { currency })}
            htmlFor="settle-net"
            hint={t('platform.form.netHint')}
          >
            <TextInput
              id="settle-net"
              type="number"
              min="0"
              step={step}
              value={netMajor}
              disabled={!selected}
              onChange={(e) => setNetMajor(e.target.value)}
            />
          </Field>
        </div>

        <Field
          label={t('platform.form.feeLabel')}
          htmlFor="settle-fee"
          hint={t('platform.form.feeHint')}
        >
          <div
            id="settle-fee"
            className="tnum flex h-[52px] items-center rounded-xl border border-line bg-paper px-4 font-mono text-[15px] text-ink-2"
          >
            {feeMinor != null ? formatMoney(feeMinor, currency, locale) : '—'}
          </div>
        </Field>

        {netExceedsGross ? (
          <p className="text-sm text-loss" role="alert">
            {t('platform.form.netExceedsGross')}
          </p>
        ) : null}

        <div className="flex justify-end">
          <Button type="submit" disabled={!canSubmit}>
            {t('platform.form.submit')}
          </Button>
        </div>
      </form>
    </Card>
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
