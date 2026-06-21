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
import { Check, Clock, Printer } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { formatMoney } from '@/lib/money'
import type { OrderResponse, PaymentResponse } from './api'

interface Props {
  order: OrderResponse
  payment: PaymentResponse
  locale: string
  /** Optional business name shown on the printed receipt. */
  businessName?: string
  /**
   * Human-readable table label resolved from the tables list (e.g. "T1").
   * Null for takeaway/delivery or when tableId is absent.
   */
  tableLabel?: string | null
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

function orderTypeI18nKey(orderType: string | null): string | null {
  switch (orderType) {
    case 'DINE_IN':
      return 'pos.orderType.dineIn'
    case 'TAKEAWAY':
      return 'pos.orderType.takeaway'
    case 'DELIVERY':
      return 'pos.orderType.delivery'
    default:
      return null
  }
}

export function ReceiptView({ order, payment, locale, businessName, tableLabel, onNew }: Props) {
  const { t } = useTranslation()
  const isPending = payment.status === 'PENDING'
  const isCash = payment.tenderType === 'CASH'
  const currency = payment.currency
  const breakdown = order.breakdown
  const orderTypeKey = orderTypeI18nKey(order.orderType ?? null)

  function handlePrint() {
    window.print()
  }

  return (
    <>
      {/* Print-only receipt (hidden on screen, shown when printing) */}
      <PrintReceipt
        order={order}
        payment={payment}
        locale={locale}
        businessName={businessName}
        tableLabel={tableLabel}
        t={t}
      />

      <div
        className="print:hidden fixed inset-0 z-40 grid place-items-center bg-ink/30 p-4 backdrop-blur-sm"
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
            {order.lines.map((line, idx) => (
              <li key={`${line.menuItemId}-${idx}`} className="px-5 py-2.5">
                <div className="flex items-baseline justify-between">
                  <div className="min-w-0 flex-1">
                    <span className="truncate text-sm text-ink">{line.name}</span>
                    <span className="ml-2 text-xs text-ink-3">× {line.qty}</span>
                  </div>
                  <span className="tnum ml-3 font-mono text-sm text-ink">
                    {formatMoney(line.lineTotalMinor, currency, locale)}
                  </span>
                </div>
                {/* Modifier snapshots */}
                {line.modifiers && line.modifiers.length > 0 ? (
                  <ul className="mt-1 space-y-0.5 pl-0">
                    {line.modifiers.map((mod) => (
                      <li
                        key={mod.optionId}
                        className="flex items-baseline justify-between text-xs text-ink-3"
                      >
                        <span>{mod.nameSnapshot}</span>
                        {mod.priceDeltaMinor !== 0 ? (
                          <span className="tnum ml-2 font-mono">
                            {mod.priceDeltaMinor > 0 ? '+' : ''}
                            {formatMoney(mod.priceDeltaMinor, currency, locale)}
                          </span>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                ) : null}
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

          {/* Order type + table */}
          {orderTypeKey ? (
            <div className="flex items-baseline justify-between">
              <span className="text-ink-3">{t('pos.orderType.label')}</span>
              <span className="font-medium text-ink">{t(orderTypeKey)}</span>
            </div>
          ) : null}

          {order.orderType === 'DINE_IN' && tableLabel ? (
            <div className="flex items-baseline justify-between">
              <span className="text-ink-3">{t('pos.table.label')}</span>
              <span className="font-medium text-ink">{tableLabel}</span>
            </div>
          ) : null}

          {/* Timestamp */}
          <div className="flex items-baseline justify-between">
            <span className="text-ink-3">{t('pos.receipt.time')}</span>
            <span className="tnum font-mono text-ink">
              {new Intl.DateTimeFormat(locale, {
                dateStyle: 'medium',
                timeStyle: 'short',
              }).format(new Date())}
            </span>
          </div>
        </div>

        {/* CTA */}
        <div className="border-t border-line px-5 py-4 flex gap-2">
          <Button variant="outline" className="flex-1" onClick={handlePrint}>
            <Printer className="size-4" />
            {t('pos.receipt.print')}
          </Button>
          <Button className="flex-1" onClick={onNew}>
            {t('pos.receipt.newOrder')}
          </Button>
        </div>
      </Card>
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// PrintReceipt — hidden on screen, visible only when window.print() is called.
// Uses inline styles to ensure thermal-style B/W output without Tailwind classes
// being toggled by the print media query.
// ---------------------------------------------------------------------------

interface PrintReceiptProps {
  order: OrderResponse
  payment: PaymentResponse
  locale: string
  businessName?: string
  tableLabel?: string | null
  t: (key: string, opts?: Record<string, unknown>) => string
}

function PrintReceipt({ order, payment, locale, businessName, tableLabel, t }: PrintReceiptProps) {
  const currency = payment.currency
  const breakdown = order.breakdown
  const isCash = payment.tenderType === 'CASH'

  const printTime = new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date())

  const orderTypeKey = (() => {
    switch (order.orderType) {
      case 'DINE_IN': return 'pos.orderType.dineIn'
      case 'TAKEAWAY': return 'pos.orderType.takeaway'
      case 'DELIVERY': return 'pos.orderType.delivery'
      default: return null
    }
  })()

  return (
    <>
      {/* Global print styles injected inline so they survive the shadow DOM / bundler */}
      <style>{`
        @media print {
          body > *:not(#native-print-receipt) { display: none !important; }
          #native-print-receipt { display: block !important; }
          @page { margin: 10mm; size: 80mm auto; }
        }
        #native-print-receipt {
          display: none;
          font-family: 'Courier New', Courier, monospace;
          font-size: 12px;
          color: #000;
          background: #fff;
          max-width: 300px;
          margin: 0 auto;
        }
        #native-print-receipt .pr-center { text-align: center; }
        #native-print-receipt .pr-bold { font-weight: bold; }
        #native-print-receipt .pr-rule { border: none; border-top: 1px dashed #000; margin: 6px 0; }
        #native-print-receipt .pr-row { display: flex; justify-content: space-between; margin: 2px 0; }
        #native-print-receipt .pr-total { font-size: 15px; font-weight: bold; }
      `}</style>

      <div id="native-print-receipt" aria-hidden="true">
        {/* Header */}
        <div className="pr-center pr-bold" style={{ fontSize: 14, marginBottom: 4 }}>
          {businessName ?? 'Native POS'}
        </div>
        <div className="pr-center" style={{ marginBottom: 2 }}>{printTime}</div>
        {orderTypeKey ? (
          <div className="pr-center" style={{ marginBottom: tableLabel ? 2 : 8 }}>{t(orderTypeKey)}</div>
        ) : null}
        {order.orderType === 'DINE_IN' && tableLabel ? (
          <div className="pr-center" style={{ marginBottom: 8 }}>{t('pos.table.label')}: {tableLabel}</div>
        ) : null}

        <hr className="pr-rule" />

        {/* Items */}
        {order.lines.map((line, idx) => (
          <div key={`${line.menuItemId}-${idx}`}>
            <div className="pr-row">
              <span>{line.name} × {line.qty}</span>
              <span>{formatMoney(line.lineTotalMinor, currency, locale)}</span>
            </div>
            {line.modifiers && line.modifiers.length > 0 ? (
              line.modifiers.map((mod) => (
                <div key={mod.optionId} className="pr-row" style={{ paddingLeft: 8, fontSize: 10 }}>
                  <span>{mod.nameSnapshot}</span>
                  {mod.priceDeltaMinor !== 0 ? (
                    <span>
                      {mod.priceDeltaMinor > 0 ? '+' : ''}
                      {formatMoney(mod.priceDeltaMinor, currency, locale)}
                    </span>
                  ) : null}
                </div>
              ))
            ) : null}
          </div>
        ))}

        <hr className="pr-rule" />

        {/* Breakdown */}
        {breakdown ? (
          <>
            <div className="pr-row"><span>{t('pos.subtotal')}</span><span>{formatMoney(breakdown.subtotalMinor, currency, locale)}</span></div>
            {breakdown.discountMinor > 0 ? (
              <div className="pr-row"><span>{t('pos.discount')}</span><span>-{formatMoney(breakdown.discountMinor, currency, locale)}</span></div>
            ) : null}
            <div className="pr-row"><span>{t('pos.serviceCharge')}</span><span>{formatMoney(breakdown.serviceChargeMinor, currency, locale)}</span></div>
            <div className="pr-row"><span>{t('pos.tax')}</span><span>{formatMoney(breakdown.taxMinor, currency, locale)}</span></div>
            <hr className="pr-rule" />
            <div className="pr-row pr-total"><span>{t('pos.receipt.total')}</span><span>{formatMoney(breakdown.grandTotalMinor, currency, locale)}</span></div>
          </>
        ) : (
          <div className="pr-row pr-total"><span>{t('pos.receipt.total')}</span><span>{formatMoney(payment.amountMinor, currency, locale)}</span></div>
        )}

        <hr className="pr-rule" />

        {/* Payment */}
        <div className="pr-row"><span>{t('pos.receipt.tender')}</span><span>{t(tenderKey(payment.tenderType))}</span></div>
        {isCash && payment.tenderedMinor != null ? (
          <div className="pr-row"><span>{t('pos.receipt.tendered')}</span><span>{formatMoney(payment.tenderedMinor, currency, locale)}</span></div>
        ) : null}
        {isCash && payment.changeMinor != null ? (
          <div className="pr-row"><span>{t('pos.receipt.change')}</span><span>{formatMoney(payment.changeMinor, currency, locale)}</span></div>
        ) : null}

        <hr className="pr-rule" />
        <div className="pr-center" style={{ marginTop: 8, fontSize: 10 }}>
          {t('pos.receipt.thankYou')}
        </div>
      </div>
    </>
  )
}
