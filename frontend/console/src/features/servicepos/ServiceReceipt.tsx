/**
 * ServiceReceipt — post-payment overlay for the service POS (carwash today). Maps a TicketResponse
 * into ThermalReceipt's normalised ThermalProps so the on-screen and printed receipts are always
 * identical (WYSIWYG) — the exact pattern features/pos/ReceiptView.tsx uses for restaurant orders.
 *
 * ThermalReceipt itself is REUSED (imported, not copied) from features/pos/ThermalReceipt — it is a
 * pure, vertical-agnostic presentation component driven entirely by its ThermalProps data model.
 *
 * Money rule (rule 8): all minor-unit amounts come from the server; formatMoney() handles scaling
 * and locale rendering (rule 9).
 */
import { useTranslation } from 'react-i18next'
import { formatMoney } from '@/lib/money'
import { ThermalReceipt } from '@/features/pos/ThermalReceipt'
import type { ThermalRow, ThermalLineItem } from '@/features/pos/ThermalReceipt'
import type { VerticalPosConfig } from './config'
import { ticketLocationOf, type AppliedPromotionResponse, type TicketResponse } from './api'

interface Props {
  config: VerticalPosConfig
  ticket: TicketResponse
  locale: string
  /** Outlet/company name shown on the receipt. */
  businessName: string
  /**
   * Phase 3 (ADR 0026): the per-rule deduction detail from the LAST live quote before payment — the
   * checkout response itself carries only the aggregate discount, so this is a display-only
   * best-effort snapshot, rendered as extra deduction rows under the discount total.
   */
  appliedPromotions?: AppliedPromotionResponse[]
  onNew: () => void
}

/** Maps Payment.Status enum strings to i18n keys — mirrors ReceiptView's private statusKey. */
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

/** Maps TenderType enum strings to i18n keys — mirrors ReceiptView's private tenderKey. */
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

export function ServiceReceipt({ config, ticket, locale, businessName, appliedPromotions, onNew }: Props) {
  const { t } = useTranslation()
  const payment = ticket.payment
  const isPending = payment?.status === 'PENDING'
  const isCash = payment?.tenderType === 'CASH'
  const currency = ticket.breakdown.currency
  const illustrative = ticket.breakdown.usesIllustrativeRules

  const reference = ticket.ticketId.slice(-8).toUpperCase()

  const dateTime = new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(ticket.occurredAt))

  // Meta rows — location (bay/chair) / vehicle / staff, skipping any that are null.
  const metaRows: ThermalRow[] = []
  const location = ticketLocationOf(config, ticket)
  if (location) {
    metaRows.push({ label: t(config.location.labelKey), valueLabel: location })
  }
  if (config.vehicleField && ticket.vehiclePlate) {
    metaRows.push({ label: t('carwashPos.vehiclePlate'), valueLabel: ticket.vehiclePlate })
  }
  if (config.attribution.enabled && ticket.staffLabel) {
    metaRows.push({ label: t(config.attribution.labelKey), valueLabel: ticket.staffLabel })
  }

  // Line items — priceMinor is the catalog UNIT price; the receipt shows the line total (× qty).
  const lineItems: ThermalLineItem[] = ticket.lines.map((line) => ({
    qty: line.qty,
    name: line.name,
    priceLabel: formatMoney(line.priceMinor * line.qty, line.currency, locale),
    modifiers: [],
  }))

  const breakdown = ticket.breakdown
  const totalRows: ThermalRow[] = [
    { label: t('pos.subtotal'), valueLabel: formatMoney(breakdown.subtotalMinor, currency, locale) },
  ]
  if (breakdown.discountMinor > 0) {
    totalRows.push({
      label: t('pos.discount'),
      valueLabel: `- ${formatMoney(breakdown.discountMinor, currency, locale)}`,
      isDeduction: true,
    })
    // Phase 3 (ADR 0026): the per-rule detail behind the aggregate discount above, from the LAST
    // live quote before payment (display-only — see the `appliedPromotions` prop doc).
    for (const promo of appliedPromotions ?? []) {
      totalRows.push({
        label: `  ${promo.name}`,
        valueLabel: `- ${formatMoney(promo.amountMinor, currency, locale)}`,
        isDeduction: true,
      })
    }
  }
  totalRows.push({
    label: illustrative ? `${t('pos.serviceCharge')} · ${t('pos.estimated')}` : t('pos.serviceCharge'),
    valueLabel: formatMoney(breakdown.serviceChargeMinor, currency, locale),
  })
  totalRows.push({
    label: illustrative ? `${t('pos.tax')} · ${t('pos.estimated')}` : t('pos.tax'),
    valueLabel: formatMoney(breakdown.taxMinor, currency, locale),
  })

  const grandTotalLabel = formatMoney(breakdown.grandTotalMinor, currency, locale)

  const paymentRows: ThermalRow[] = []
  if (payment) {
    paymentRows.push({ label: t('pos.receipt.tender'), valueLabel: t(tenderKey(payment.tenderType)) })
    if (isCash && payment.tenderedMinor != null) {
      paymentRows.push({
        label: t('pos.receipt.tendered'),
        valueLabel: formatMoney(payment.tenderedMinor, currency, locale),
      })
    }
    if (isCash && payment.changeMinor != null) {
      paymentRows.push({
        label: t('pos.receipt.change'),
        valueLabel: formatMoney(payment.changeMinor, currency, locale),
      })
    }
    paymentRows.push({ label: t('pos.receipt.status'), valueLabel: t(statusKey(payment.status)) })
  }
  paymentRows.push({ label: t('pos.receipt.time'), valueLabel: dateTime })

  return (
    <ThermalReceipt
      businessName={businessName}
      title={t('servicePos.receipt.title')}
      reference={reference}
      dateTime={dateTime}
      metaRows={metaRows}
      lineItems={lineItems}
      totalRows={totalRows}
      grandTotalLabel={grandTotalLabel}
      paymentRows={paymentRows}
      footerNote={t('pos.receipt.thankYou')}
      onPrint={() => window.print()}
      onAction={onNew}
      actionLabel={t('servicePos.receipt.newTicket')}
      isPending={!!(isPending && payment?.providerPending)}
      pendingNote={isPending && payment?.providerPending ? t('pos.receipt.pendingNote') : undefined}
    />
  )
}
