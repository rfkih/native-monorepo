/**
 * ReceiptView — post-payment overlay (ADR 0006, slice 5).
 *
 * Shows after a successful CASH pay (CAPTURED) or QRIS/CARD capture (CAPTURED),
 * and also after a PENDING capture confirmation if the operator needs to see the
 * provisional state.
 *
 * Delegates all rendering to ThermalReceipt. Maps order + payment data into the
 * normalised ThermalProps model so the on-screen receipt and the printed receipt
 * are always identical (WYSIWYG).
 *
 * Money rule (rule 8): all minor-unit amounts come from the server; formatMoney()
 * handles scaling and locale rendering (rule 9).
 */
import { useTranslation } from 'react-i18next'
import { formatMoney } from '@/lib/money'
import { printCurrentPage } from '@/lib/nativeShell'
import type { AppliedPromotionResponse, OrderResponse, PaymentResponse } from './api'
import { ThermalReceipt } from './ThermalReceipt'
import type { ThermalRow, ThermalLineItem, ThermalSecondaryAction } from './ThermalReceipt'

interface Props {
  order: OrderResponse
  payment: PaymentResponse
  locale: string
  /** Optional business name shown on the receipt. */
  businessName?: string
  /**
   * Human-readable table label resolved from the tables list (e.g. "T1").
   * Null for takeaway/delivery or when tableId is absent.
   */
  tableLabel?: string | null
  /**
   * Phase 3 (ADR 0026): the per-rule deduction detail from the LAST live quote before payment —
   * the checkout/pay-parked response itself carries only the aggregate `discountMinor` (the
   * per-rule detail is durably persisted server-side, not echoed there), so this is a display-only
   * best-effort snapshot, rendered as extra deduction rows under the discount total.
   */
  appliedPromotions?: AppliedPromotionResponse[]
  /** Phase 5 (ADR 0028): true for a client-side receipt generated while offline — the sale is
   * queued and not yet confirmed by the server. Renders a solid provisional marker (ThermalReceipt). */
  provisional?: boolean
  /**
   * Reprint mode (today's-sales history): the header shows the ORIGINAL sale time (`occurredAt`),
   * a "reprint" meta row marks the copy, and auto-print is suppressed — reprinting is always a
   * deliberate button press, never a side effect of opening the history entry.
   */
  reprint?: boolean
  /** ISO instant of the original sale — header time for a reprint (defaults to now). */
  occurredAt?: string
  /** Overrides the bottom action label (default: "new order"). */
  actionLabelText?: string
  /**
   * Optional destructive action below the Print/primary row — the manager-gated "Return sale"
   * refund (ADR 0061). The caller owns eligibility (owner/manager ∧ CAPTURED ∧ not provisional) and
   * the confirm/refund flow; ReceiptView only forwards it to the receipt's button area.
   */
  secondaryAction?: ThermalSecondaryAction
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

/**
 * The reversed-sale banner text (ThermalReceipt's `reversedNote`, on screen AND on the printed
 * thermal paper) for a VOIDED/REFUNDED/PARTIALLY_REFUNDED payment — null for any other status, so
 * a normal paid receipt never grows one. The partial variant names the refunded amount so the
 * banner is actionable, not just a status word.
 */
function reversedBannerNote(
  t: (key: string, opts?: Record<string, unknown>) => string,
  payment: PaymentResponse,
  locale: string,
): string | null {
  switch (payment.status) {
    case 'VOIDED':
      return t('pos.receipt.voidedBanner')
    case 'REFUNDED':
      return t('pos.receipt.refundedBanner')
    case 'PARTIALLY_REFUNDED':
      return t('pos.receipt.partiallyRefundedBanner', {
        amount: formatMoney(payment.refundedMinor ?? 0, payment.currency, locale),
      })
    default:
      return null
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

export function ReceiptView({
  order,
  payment,
  locale,
  businessName,
  tableLabel,
  appliedPromotions,
  provisional,
  reprint,
  occurredAt,
  actionLabelText,
  secondaryAction,
  onNew,
}: Props) {
  const { t } = useTranslation()
  const isPending = payment.status === 'PENDING'
  const isCash = payment.tenderType === 'CASH'
  const currency = payment.currency
  const reversedNote = reversedBannerNote(t, payment, locale)
  const breakdown = order.breakdown
  const orderTypeKey = orderTypeI18nKey(order.orderType ?? null)

  // Short reference — last 8 chars of orderId
  const reference = order.orderId.slice(-8).toUpperCase()

  // Formatted date/time — a reprint shows the ORIGINAL sale moment, not "now".
  const dateTimeFormat = new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
  const dateTime = dateTimeFormat.format(occurredAt ? new Date(occurredAt) : new Date())

  // Tagline: order type label
  const tagline = orderTypeKey ? t(orderTypeKey) : undefined

  // Meta rows (table for dine-in)
  const metaRows: ThermalRow[] = []
  if (order.orderType === 'DINE_IN' && tableLabel) {
    metaRows.push({ label: t('pos.table.label'), valueLabel: tableLabel })
  }
  // A copy must say it is one — and when it was made.
  if (reprint) {
    metaRows.push({ label: t('pos.history.reprintLabel'), valueLabel: dateTimeFormat.format(new Date()) })
  }

  // Line items
  const lineItems: ThermalLineItem[] = order.lines.map((line) => ({
    qty: line.qty,
    name: line.name,
    priceLabel: formatMoney(line.lineTotalMinor, currency, locale),
    modifiers: (line.modifiers ?? []).map((mod) => ({
      label: mod.nameSnapshot,
      deltaLabel:
        mod.priceDeltaMinor !== 0
          ? `${mod.priceDeltaMinor > 0 ? '+' : ''}${formatMoney(mod.priceDeltaMinor, currency, locale)}`
          : undefined,
    })),
  }))

  // Total rows
  const totalRows: ThermalRow[] = []
  if (breakdown) {
    totalRows.push({ label: t('pos.subtotal'), valueLabel: formatMoney(breakdown.subtotalMinor, currency, locale) })
    if (breakdown.discountMinor > 0) {
      totalRows.push({
        label: t('pos.discount'),
        valueLabel: `- ${formatMoney(breakdown.discountMinor, currency, locale)}`,
        isDeduction: true,
      })
      // Phase 3 (ADR 0026): the per-rule detail behind the aggregate discount above, from the LAST
      // live quote before payment (display-only). The aggregate line is authoritative (checkout
      // response); the itemization is a quote snapshot that can drift (happy hour ended, coupon
      // raced) — suppress it when its sum EXCEEDS the aggregate, so a printed receipt never shows
      // rows adding up to more than the discount actually charged. A sum below the aggregate is
      // legitimate (the remainder is the manual discount, which has no rule row). Review W3.
      const promoRows = appliedPromotions ?? []
      const promoSum = promoRows.reduce((sum, p) => sum + p.amountMinor, 0)
      if (promoSum <= breakdown.discountMinor) {
        for (const promo of promoRows) {
          totalRows.push({
            label: `  ${promo.name}`,
            valueLabel: `- ${formatMoney(promo.amountMinor, currency, locale)}`,
            isDeduction: true,
          })
        }
      }
    }
    // Phase 4 (ADR 0027): loyalty points redeemed — a separate deduction row under the discount
    // block (never folded into the promo-only discount above, matching the wire's split).
    if (breakdown.loyaltyRedeemedMinor > 0) {
      totalRows.push({
        label: t('pos.loyalty.redeemedLabel'),
        valueLabel: `- ${formatMoney(breakdown.loyaltyRedeemedMinor, currency, locale)}`,
        isDeduction: true,
      })
    }
    totalRows.push({ label: t('pos.serviceCharge'), valueLabel: formatMoney(breakdown.serviceChargeMinor, currency, locale) })
    totalRows.push({ label: t('pos.tax'), valueLabel: formatMoney(breakdown.taxMinor, currency, locale) })
  } else {
    totalRows.push({ label: t('pos.receipt.subtotal'), valueLabel: formatMoney(order.totalMinor, currency, locale) })
  }

  // Grand total
  const grandTotalLabel = breakdown
    ? formatMoney(breakdown.grandTotalMinor, currency, locale)
    : formatMoney(payment.amountMinor, currency, locale)

  // Payment rows. Phase 4 (ADR 0027): a gift-card tender is a PAYMENT row (never a discount) — it
  // is listed here, before the tender-in-hand row, when the breakdown carries one. Points EARNED
  // on this sale are deliberately NOT shown — MemberResponse/OrderResponse never echo an
  // earned-points figure back on the checkout/pay-parked response, so there is nothing to render
  // (a client-side estimate would risk drifting from the server's actual ledger entry).
  const paymentRows: ThermalRow[] = []
  if (breakdown && breakdown.giftCardAppliedMinor > 0) {
    paymentRows.push({
      label: t('pos.loyalty.giftCard.paymentRowLabel'),
      valueLabel: `- ${formatMoney(breakdown.giftCardAppliedMinor, currency, locale)}`,
      isDeduction: true,
    })
  }
  paymentRows.push({ label: t('pos.receipt.tender'), valueLabel: t(tenderKey(payment.tenderType)) })
  if (isCash && payment.tenderedMinor != null) {
    paymentRows.push({ label: t('pos.receipt.tendered'), valueLabel: formatMoney(payment.tenderedMinor, currency, locale) })
  }
  if (isCash && payment.changeMinor != null) {
    paymentRows.push({ label: t('pos.receipt.change'), valueLabel: formatMoney(payment.changeMinor, currency, locale) })
  }
  // Paper efficiency: Status prints only when it says something ("Paid" on every normal receipt
  // was noise; Pending/Voided/Refunded still matter). Time was a byte-identical duplicate of the
  // header dateTime — dropped.
  if (payment.status !== 'CAPTURED') {
    paymentRows.push({ label: t('pos.receipt.status'), valueLabel: t(statusKey(payment.status)) })
  }

  return (
    <ThermalReceipt
      autoPrint={!reprint}
      cashTender={isCash}
      businessName={businessName ?? 'Native POS'}
      title={t('pos.receipt.title')}
      reference={reference}
      tagline={tagline}
      dateTime={dateTime}
      metaRows={metaRows}
      lineItems={lineItems}
      totalRows={totalRows}
      grandTotalLabel={grandTotalLabel}
      paymentRows={paymentRows}
      footerNote={t('pos.receipt.thankYou')}
      onPrint={() => printCurrentPage('receipt')}
      onAction={onNew}
      actionLabel={actionLabelText ?? t('pos.receipt.newOrder')}
      secondaryAction={secondaryAction}
      isPending={isPending}
      pendingNote={isPending ? t('pos.receipt.pendingNote') : undefined}
      isProvisional={provisional}
      provisionalNote={provisional ? t('offline.provisional.receiptNote') : undefined}
      isReversed={reversedNote != null}
      reversedNote={reversedNote ?? undefined}
    />
  )
}
