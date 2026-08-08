/**
 * SalesHistorySheet — the cashier's "today's transactions" overlay (till menu). Lists the
 * outlet's sales for the local day (server truth — offline-queued sales appear once synced;
 * the SyncCenter owns the pending queue) and reopens any sale's receipt for a REPRINT via
 * the same ReceiptView/ThermalReceipt path as the original — printer transports included.
 *
 * Money rule (rule 8): server minor units through formatMoney only. Strings rule (rule 9):
 * i18n keys only.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { History, ReceiptText, TriangleAlert, X } from 'lucide-react'
import { Spinner } from '@/components/ui/Spinner'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { ReceiptView } from './ReceiptView'
import { useOrderReceipt, useSalesHistory, type SaleHistoryRow } from './salesHistoryApi'

/** Tender → existing i18n label; ONLINE shows the channel's own immutable code. */
function tenderLabel(t: (k: string) => string, row: SaleHistoryRow): string {
  switch (row.tenderType) {
    case 'CASH':
      return t('pos.payment.tenderCash')
    case 'QRIS':
      return t('pos.payment.tenderQris')
    case 'CARD':
      return t('pos.payment.tenderCard')
    case 'ONLINE':
      return row.channelCode ?? t('pos.history.tenderOnline')
    default:
      return row.tenderType ?? '—'
  }
}

export function SalesHistorySheet({
  session,
  locale,
  onClose,
}: {
  session: CompanySession
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const history = useSalesHistory(session, true)
  const [selected, setSelected] = useState<SaleHistoryRow | null>(null)
  const order = useOrderReceipt(session, selected?.orderId ?? null)

  const rows = history.data ?? []
  const currency = rows[0]?.currency ?? session.baseCurrency
  const totalMinor = rows.reduce((sum, r) => sum + r.amountMinor, 0)
  // The server caps the list at its newest 200 rows. At the cap the sum is NOT the day's
  // total any more — never present a truncated figure as if it were (review W2).
  const capped = rows.length >= 200
  const timeFormat = new Intl.DateTimeFormat(locale, { timeStyle: 'short' })

  // A selected row with a loaded order + payment renders the receipt overlay ON TOP of the
  // list (ReceiptView is its own fixed overlay); closing it returns to the list.
  const receiptReady = selected != null && order.data != null && order.data.payment != null

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-paper"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.history.title')}
    >
      <header className="flex h-14 shrink-0 items-center gap-2.5 border-b border-line bg-surface px-4">
        <History className="size-[18px] text-emerald-2" aria-hidden />
        <span className="min-w-0 flex-1 truncate text-[16px] font-bold text-ink">
          {t('pos.history.title')}
        </span>
        {rows.length > 0 && !capped ? (
          <span className="tnum font-mono text-[13px] font-semibold text-ink-2">
            {formatMoney(totalMinor, currency, locale)}
          </span>
        ) : null}
        {capped ? (
          <span className="text-[11.5px] font-semibold text-amber-2">
            {t('pos.history.capNote')}
          </span>
        ) : null}
        <button
          type="button"
          onClick={onClose}
          aria-label={t('common.close')}
          className="grid size-11 place-items-center rounded-full text-ink-3 hover:bg-hover hover:text-ink"
        >
          <X className="size-5" aria-hidden />
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto px-3 py-3">
        {history.isLoading ? (
          <ListSkeleton rows={6} />
        ) : history.isError ? (
          <div className="mx-auto mt-10 max-w-sm rounded-2xl border border-loss/30 bg-tint-loss px-6 py-6 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" aria-hidden />
            {t('pos.history.error')}
          </div>
        ) : rows.length === 0 ? (
          <div className="mx-auto mt-14 max-w-sm text-center">
            <ReceiptText className="mx-auto mb-3 size-8 text-ink-3/50" aria-hidden />
            <p className="text-sm font-semibold text-ink">{t('pos.history.emptyTitle')}</p>
            <p className="mt-1 text-[13px] text-ink-3">{t('pos.history.emptyHint')}</p>
          </div>
        ) : (
          <div className="mx-auto flex max-w-[640px] flex-col gap-1.5">
            {rows.map((row) => {
              const openable = row.orderId != null
              return (
                <button
                  key={row.saleId}
                  type="button"
                  disabled={!openable}
                  onClick={() => setSelected(row)}
                  className={cn(
                    'flex w-full items-center gap-3 rounded-2xl border border-line bg-surface px-3.5 py-3 text-left',
                    openable
                      ? 'transition-colors hover:border-emerald-line hover:bg-emerald-tint/40'
                      : 'opacity-60',
                  )}
                >
                  <span className="tnum w-[52px] shrink-0 font-mono text-[13.5px] font-bold text-ink">
                    {timeFormat.format(new Date(row.occurredAt))}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13.5px] font-semibold text-ink-2">
                      {tenderLabel(t, row)}
                    </span>
                    <span className="tnum mt-0.5 block font-mono text-[11px] text-ink-3">
                      {row.orderId != null
                        ? row.orderId.slice(-8).toUpperCase()
                        : t('pos.history.noReceipt')}
                    </span>
                  </span>
                  <span className="tnum shrink-0 font-mono text-[14px] font-bold text-ink">
                    {formatMoney(row.amountMinor, row.currency, locale)}
                  </span>
                </button>
              )
            })}
          </div>
        )}
      </div>

      {/* Reprint flow states — the order fetch behind a tapped row. */}
      {selected != null && order.isLoading ? (
        <div className="absolute inset-0 z-10 grid place-items-center bg-black/30">
          <Spinner className="text-white" />
        </div>
      ) : null}
      {selected != null && !order.isLoading && (order.isError || (order.data != null && order.data.payment == null)) ? (
        <div className="absolute inset-x-0 bottom-0 z-10 border-t border-line bg-surface p-4 pb-6">
          <p className="text-center text-sm text-loss">{t('pos.history.receiptUnavailable')}</p>
          <button
            type="button"
            onClick={() => setSelected(null)}
            className="mx-auto mt-3 block rounded-xl border border-line px-5 py-2 text-sm font-semibold text-ink"
          >
            {t('common.close')}
          </button>
        </div>
      ) : null}
      {receiptReady ? (
        <ReceiptView
          order={order.data!}
          payment={order.data!.payment!}
          locale={locale}
          businessName={session.name}
          tableLabel={null}
          reprint
          occurredAt={selected!.occurredAt}
          actionLabelText={t('common.close')}
          onNew={() => setSelected(null)}
        />
      ) : null}
    </div>
  )
}
