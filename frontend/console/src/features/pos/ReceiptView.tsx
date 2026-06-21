/**
 * ReceiptView — post-payment overlay (ADR 0006, slice 5).
 *
 * Shows after a successful CASH pay (CAPTURED) or QRIS/CARD capture (CAPTURED),
 * and also after a PENDING capture confirmation if the operator needs to see the
 * provisional state.
 *
 * Uses the data already in memory (checkout + capture responses) so no extra
 * network call is needed for the happy path. The useReceipt hook is exported
 * from api.ts for any future "re-print" feature.
 *
 * Phase 2: renders the full price breakdown from order.breakdown (subtotal,
 * discount, service charge, tax, grand total) when present.
 *
 * Money rule (rule 8): all minor-unit amounts come from the server; formatMoney()
 * handles scaling and locale rendering (rule 9).
 */
import { useTranslation } from 'react-i18next'
import { Check, Clock } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { formatMoney } from '@/lib/money'
import type { OrderResponse, PaymentResponse } from './api'

interface Props {
  order: OrderResponse
  payment: PaymentResponse
  locale: string
  onNew: () => void
}

/** Maps Payment.Status enum strings to i18n keys. */
function statusKey(status: string): string {
  switch (status) {
    case 'CAPTURED':
      return 'pos.receipt.statusCaptured'
    case 'PENDING':
      return 'pos.receipt.statusPending'
    case 'VOIDED':
      return 'pos.receipt.statusVoided'
    case 'REFUNDED':
      return 'pos.receipt.statusRefunded'
    case 'PARTIALLY_REFUNDED':
      return 'pos.receipt.statusPartiallyRefunded'
    default:
      return 'pos.receipt.statusPending'
  }
}

/** Maps TenderType enum strings to i18n keys. */
function tenderKey(tenderType: string): string {
  switch (tenderType) {
    case 'CASH':
      return 'pos.payment.tenderCash'
    case 'QRIS':
      return 'pos.payment.tenderQris'
    case 'CARD':
      return 'pos.payment.tenderCard'
    default:
      return tenderType
  }
}

export function ReceiptView({ order, payment, locale, onNew }: Props) {
  const { t } = useTranslation()
  const isPending = payment.status === 'PENDING'
  const isCash = payment.tenderType === 'CASH'
  const currency = payment.currency
  const breakdown = order.breakdown

  return (
    <div
      className="fixed inset-0 z-40 grid place-items-center bg-ink/30 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.receipt.title')}
    >
      <Card className="reveal w-full max-w-sm overflow-hidden">
        {/* Status icon */}
        <div className="flex flex-col items-center px-5 pt-7 pb-4 text-center">
          {isPending ? (
            <div className="mx-auto grid size-14 place-items-center rounded-full bg-amber-tint text-amber-2">
              <Clock className="size-7" />
            </div>
          ) : (
            <div className="mx-auto grid size-14 place-items-center rounded-full bg-emerald text-white">
              <Check className="size-7" />
            </div>
          )}

          <h2 className="mt-4 font-display text-xl font-semibold text-ink">
            {t('pos.receipt.title')}
          </h2>

          {isPending && payment.providerPending ? (
            <Badge tone="amber" className="mt-2">
              {t('pos.payment.providerPendingBadge')}
            </Badge>
          ) : null}
        </div>

        {/* Pending note */}
        {isPending ? (
          <div className="mx-5 mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-2.5 text-xs leading-relaxed text-amber-2">
            {t('pos.receipt.pendingNote')}
          </div>
        ) : null}

        {/* Line items */}
        <div className="border-t border-line">
          <div className="px-5 pt-3 pb-1">
            <p className="text-xs font-semibold uppercase tracking-wider text-ink-3">
              {t('pos.receipt.items')}
            </p>
          </div>
          <ul className="divide-y divide-line">
            {order.lines.map((line) => (
              <li key={line.menuItemId} className="flex items-baseline justify-between px-5 py-2.5">
                <div className="min-w-0 flex-1">
                  <span className="truncate text-sm text-ink">{line.name}</span>
                  <span className="ml-2 text-xs text-ink-3">× {line.qty}</span>
                </div>
                <span className="tnum ml-3 font-mono text-sm text-ink">
                  {formatMoney(line.lineTotalMinor, currency, locale)}
                </span>
              </li>
            ))}
          </ul>
        </div>

        {/* Price breakdown block */}
        <div className="border-t border-line px-5 py-4 space-y-2">
          {breakdown ? (
            <>
              {/* Subtotal */}
              <div className="flex items-baseline justify-between text-sm text-ink-3">
                <span>{t('pos.subtotal')}</span>
                <span className="tnum font-mono">
                  {formatMoney(breakdown.subtotalMinor, currency, locale)}
                </span>
              </div>

              {/* Discount — only when applied */}
              {breakdown.discountMinor > 0 ? (
                <div className="flex items-baseline justify-between text-sm text-ink-3">
                  <span>{t('pos.discount')}</span>
                  <span className="tnum font-mono text-rose">
                    − {formatMoney(breakdown.discountMinor, currency, locale)}
                  </span>
                </div>
              ) : null}

              {/* Service charge */}
              <div className="flex items-center justify-between text-sm text-ink-3">
                <span className="flex items-center gap-1.5">
                  {t('pos.serviceCharge')}
                  {breakdown.usesIllustrativeRules ? (
                    <Badge tone="amber" className="text-[10px] py-0 px-1.5">
                      {t('pos.estimated')}
                    </Badge>
                  ) : null}
                </span>
                <span className="tnum font-mono">
                  {formatMoney(breakdown.serviceChargeMinor, currency, locale)}
                </span>
              </div>

              {/* Tax */}
              <div className="flex items-center justify-between text-sm text-ink-3">
                <span className="flex items-center gap-1.5">
                  {t('pos.tax')}
                  {breakdown.usesIllustrativeRules ? (
                    <Badge tone="amber" className="text-[10px] py-0 px-1.5">
                      {t('pos.estimated')}
                    </Badge>
                  ) : null}
                </span>
                <span className="tnum font-mono">
                  {formatMoney(breakdown.taxMinor, currency, locale)}
                </span>
              </div>

              {/* Grand total */}
              <div className="flex items-baseline justify-between border-t border-line pt-2 font-medium">
                <span className="text-sm text-ink">{t('pos.receipt.total')}</span>
                <span className="tnum font-mono text-xl text-ink">
                  {formatMoney(breakdown.grandTotalMinor, currency, locale)}
                </span>
              </div>
            </>
          ) : (
            <>
              {/* Fallback for orders without a breakdown (idempotent re-reads) */}
              <div className="flex items-baseline justify-between text-sm text-ink-3">
                <span>{t('pos.receipt.subtotal')}</span>
                <span className="tnum font-mono">{formatMoney(order.totalMinor, currency, locale)}</span>
              </div>
              <div className="flex items-baseline justify-between font-medium">
                <span className="text-sm text-ink">{t('pos.receipt.total')}</span>
                <span className="tnum font-mono text-xl text-ink">
                  {formatMoney(payment.amountMinor, currency, locale)}
                </span>
              </div>
            </>
          )}
        </div>

        {/* Payment details */}
        <div className="border-t border-line px-5 py-4 space-y-2 text-sm">
          <div className="flex items-baseline justify-between">
            <span className="text-ink-3">{t('pos.receipt.tender')}</span>
            <span className="font-medium text-ink">{t(tenderKey(payment.tenderType))}</span>
          </div>

          {isCash && payment.tenderedMinor != null ? (
            <div className="flex items-baseline justify-between">
              <span className="text-ink-3">{t('pos.receipt.tendered')}</span>
              <span className="tnum font-mono text-ink">
                {formatMoney(payment.tenderedMinor, currency, locale)}
              </span>
            </div>
          ) : null}

          {isCash && payment.changeMinor != null ? (
            <div className="flex items-baseline justify-between">
              <span className="text-ink-3">{t('pos.receipt.change')}</span>
              <span className="tnum font-mono font-medium text-emerald-2">
                {formatMoney(payment.changeMinor, currency, locale)}
              </span>
            </div>
          ) : null}

          <div className="flex items-baseline justify-between">
            <span className="text-ink-3">{t('pos.receipt.status')}</span>
            <Badge tone={isPending ? 'amber' : 'emerald'}>{t(statusKey(payment.status))}</Badge>
          </div>
        </div>

        {/* CTA */}
        <div className="border-t border-line px-5 py-4">
          <Button className="w-full" onClick={onNew}>
            {t('pos.receipt.newOrder')}
          </Button>
        </div>
      </Card>
    </div>
  )
}
