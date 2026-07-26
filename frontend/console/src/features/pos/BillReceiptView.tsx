/**
 * BillReceiptView — customer receipt after paying a bill or a split check.
 *
 * Shown after usePayBill succeeds. For a split check it renders only the paid
 * lines; for a full-bill pay it renders all lines.
 *
 * Delegates all rendering to ThermalReceipt so both the on-screen display and
 * the window.print() output are identical (WYSIWYG).
 *
 * Money rule (rule 8): all amounts are integer minor units, formatted via formatMoney().
 * Strings rule (rule 9): all user-facing text is an i18n key.
 */
import { useTranslation } from 'react-i18next'
import { formatMoney } from '@/lib/money'
import type { BillLineResponse, BillResponse } from './billsApi'
import { ThermalReceipt } from './ThermalReceipt'
import type { ThermalRow, ThermalLineItem } from './ThermalReceipt'

interface Props {
  bill: BillResponse
  /** The lines that were paid in this check (all lines for full-bill, subset for split). */
  paidLines: BillLineResponse[]
  /** Total paid in this check, in minor units. */
  checkTotalMinor: number
  /** Tender type used for this check. */
  tenderType: 'CASH' | 'QRIS' | 'CARD'
  /** Tendered amount in minor units (CASH only). */
  tenderedMinor?: number
  /** Change in minor units (CASH only). */
  changeMinor?: number
  locale: string
  businessName?: string
  tableLabel?: string | null
  onClose: () => void
}

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

export function BillReceiptView({
  bill,
  paidLines,
  checkTotalMinor,
  tenderType,
  tenderedMinor,
  changeMinor,
  locale,
  businessName,
  tableLabel,
  onClose,
}: Props) {
  const { t } = useTranslation()
  const currency = bill.currency
  const isCash = tenderType === 'CASH'

  // Short reference — last 8 chars of bill id
  const reference = bill.id.slice(-8).toUpperCase()

  // Formatted date/time
  const dateTime = new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date())

  // Tagline: guest label
  const tagline = bill.guestLabel

  // Meta rows
  const metaRows: ThermalRow[] = []
  if (tableLabel) {
    metaRows.push({ label: t('pos.table.label'), valueLabel: tableLabel })
  }

  // Line items
  const lineItems: ThermalLineItem[] = paidLines.map((line) => ({
    qty: line.qty,
    name: line.nameSnapshot,
    priceLabel: formatMoney(line.lineTotalMinor, currency, locale),
    modifiers: line.modifiers.map((mod) => ({
      label: mod.nameSnapshot,
      deltaLabel:
        mod.priceDeltaMinor !== 0
          ? `${mod.priceDeltaMinor > 0 ? '+' : ''}${formatMoney(mod.priceDeltaMinor, currency, locale)}`
          : undefined,
    })),
  }))

  // Total rows — bill receipt has only one row (the check total)
  const totalRows: ThermalRow[] = []

  // Grand total
  const grandTotalLabel = formatMoney(checkTotalMinor, currency, locale)

  // Payment rows
  const paymentRows: ThermalRow[] = [
    { label: t('pos.receipt.tender'), valueLabel: t(tenderKey(tenderType)) },
  ]
  if (isCash && tenderedMinor != null) {
    paymentRows.push({ label: t('pos.receipt.tendered'), valueLabel: formatMoney(tenderedMinor, currency, locale) })
  }
  if (isCash && changeMinor != null) {
    paymentRows.push({ label: t('pos.receipt.change'), valueLabel: formatMoney(changeMinor, currency, locale) })
  }
  paymentRows.push({ label: t('pos.receipt.time'), valueLabel: dateTime })

  return (
    <ThermalReceipt
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
      onPrint={() => window.print()}
      onAction={onClose}
      actionLabel={t('common.close')}
    />
  )
}
