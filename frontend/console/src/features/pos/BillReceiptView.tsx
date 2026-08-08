/**
 * BillReceiptView — customer receipt after paying a bill or a split check.
 *
 * Shown after usePayBill succeeds — once per check payment, split or full. For
 * a split check it renders only the paid lines, plus the same-bill linkage the
 * cashier hands out with each partial receipt: the guest name and table as
 * labeled rows, a "Split check — N of M items" marker, and the remaining
 * balance after this check. Every check of one bill prints the same Ref #.
 *
 * Delegates all rendering to ThermalReceipt so both the on-screen display and
 * the window.print() output are identical (WYSIWYG). Mounts at z-[70] — above
 * the z-50 bill sheet (same layering as KotView).
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
  tenderType: 'CASH' | 'QRIS' | 'CARD' | 'ONLINE'
  /** Tendered amount in minor units (CASH only). */
  tenderedMinor?: number
  /** Change in minor units (CASH only). */
  changeMinor?: number
  /** Outstanding bill balance right after this check, in minor units (0 on the final check). */
  remainingMinor?: number
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
    case 'ONLINE':
      return 'pos.payment.tenderOnline'
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
  remainingMinor,
  locale,
  businessName,
  tableLabel,
  onClose,
}: Props) {
  const { t } = useTranslation()
  const currency = bill.currency
  const isCash = tenderType === 'CASH'

  // Short reference — last 8 chars of bill id; identical on every check of the same bill.
  const reference = bill.id.slice(-8).toUpperCase()

  // Formatted date/time
  const dateTime = new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date())

  // This check covers only part of the bill (a split check, or the remainder after one).
  const paidQty = paidLines.reduce((s, l) => s + l.qty, 0)
  const totalQty = bill.lines.reduce((s, l) => s + l.qty, 0)
  const isPartial = paidQty < totalQty

  // Meta rows — guest + table are labeled so a partial receipt is traceable to its bill.
  const metaRows: ThermalRow[] = [{ label: t('pos.receipt.guest'), valueLabel: bill.guestLabel }]
  if (tableLabel) {
    metaRows.push({ label: t('pos.table.label'), valueLabel: tableLabel })
  }
  if (isPartial) {
    metaRows.push({
      label: t('pos.receipt.splitCheck'),
      valueLabel: t('pos.receipt.splitItems', { paid: paidQty, total: totalQty }),
    })
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
  // Balance still open on the bill — printed on every partial receipt (Rp 0 on the final check).
  if (isPartial && remainingMinor != null) {
    paymentRows.push({ label: t('pos.receipt.remaining'), valueLabel: formatMoney(remainingMinor, currency, locale) })
  }
  // Time row dropped — byte-identical duplicate of the header dateTime (paper efficiency).

  return (
    <ThermalReceipt
      autoPrint
      cashTender={isCash}
      zIndexClass="z-[70]"
      businessName={businessName ?? 'Native POS'}
      title={t('pos.receipt.title')}
      reference={reference}
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
