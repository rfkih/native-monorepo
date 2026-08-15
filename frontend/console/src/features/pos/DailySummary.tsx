/**
 * DailySummary — the POS daily transaction summary (Z-report) overlay. Reached from BOTH the till
 * menu ("Ringkasan hari ini", any time) and the closing sheet's "Cetak ringkasan" button (X-report
 * on the close form, final Z-report on the after-close verdict).
 *
 * It resolves the register session to summarize — an explicit `sessionId` (the close flow passes the
 * current/just-closed session) wins; otherwise the outlet's OPEN session, else its most recent
 * CLOSED session (so a summary is still printable after the drawer is shut). With no session at all
 * it shows an empty state (open the drawer first). The server figure is rendered through the SAME
 * {@link ThermalReceipt} the receipts use, so it inherits device printing, the window.print()
 * fallback, and the reconnect/retry banner.
 *
 * Money rule (rule 8): server minor units through formatMoney only. Strings rule (rule 9): i18n keys
 * only; the transaction COUNT is the one non-money numeric (locale-formatted). Reporting only — the
 * tax line is PB1 ("Pajak Restoran"), badged "estimasi" when the rate is illustrative, and the
 * footer states it is not a tax invoice.
 */
import { useTranslation } from 'react-i18next'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { ThermalReceipt, type ThermalRow, type ThermalLineItem } from './ThermalReceipt'
import { useItemSales, type ItemSalesResponse } from './api'
import {
  useCurrentRegisterSession,
  useLastClosedSession,
  useRegisterSummary,
  type RegisterSummaryResponse,
} from './registerApi'

// Per-tender label keys — the same keys the close sheet uses (rule 9); GIFT_CARD is the 5th
// settlement type on the summary (gift-card redemption), its own key.
const TENDER_LABEL_KEY: Record<string, string> = {
  CASH: 'register.tenderCash',
  CARD: 'register.tenderCard',
  QRIS: 'register.tenderQris',
  ONLINE: 'register.tenderOnline',
  GIFT_CARD: 'register.tenderGiftCard',
}

export function DailySummary({
  session,
  locale,
  sessionId: explicitId,
  onClose,
}: {
  session: CompanySession
  locale: string
  /** The close flow passes the current/just-closed session; the till menu omits it (resolve below). */
  sessionId?: string | null
  onClose: () => void
}) {
  const { t } = useTranslation()

  // Resolve the session to summarize: explicit → current OPEN → most recent CLOSED.
  const currentQuery = useCurrentRegisterSession(session)
  const openId = currentQuery.data?.id ?? null
  const needLastClosed = !explicitId && !openId && !currentQuery.isLoading
  const lastClosedQuery = useLastClosedSession(session, needLastClosed)
  const resolvedId = explicitId ?? openId ?? lastClosedQuery.data?.id ?? null

  const summaryQuery = useRegisterSummary(session, resolvedId, true)
  const summaryData = summaryQuery.data
  // Per-item breakdown over the SAME window the summary reports (openedAt → asOf). Dependent on the
  // summary — disabled until it resolves; a failure degrades to no items, never blocks the report.
  const itemsQuery = useItemSales(session, summaryData?.openedAt ?? null, summaryData?.asOf ?? null)

  const resolving =
    (!explicitId && currentQuery.isLoading) || (needLastClosed && lastClosedQuery.isLoading)
  const loading =
    resolving ||
    (resolvedId != null && summaryQuery.isLoading) ||
    (summaryData != null && itemsQuery.isLoading)

  if (loading) {
    return (
      <div
        className="fixed inset-0 z-[70] grid place-items-center bg-black/60 backdrop-blur-sm"
        role="dialog"
        aria-modal="true"
        aria-label={t('register.summaryTitle')}
      >
        <Spinner className="text-white" />
      </div>
    )
  }

  const summary = summaryQuery.data
  if (!resolvedId || summaryQuery.isError || !summary) {
    return (
      <div
        className="fixed inset-0 z-[70] grid place-items-center bg-black/60 p-6 backdrop-blur-sm"
        role="dialog"
        aria-modal="true"
        aria-label={t('register.summaryTitle')}
      >
        <div className="w-full max-w-sm rounded-card border border-line bg-surface px-6 py-6 text-center">
          <p className="text-sm font-semibold text-ink">{t('register.summaryTitle')}</p>
          <p className="mt-1.5 text-[13px] text-ink-3">
            {summaryQuery.isError ? t('register.summaryError') : t('register.summaryEmpty')}
          </p>
          <button
            type="button"
            onClick={onClose}
            className="mx-auto mt-4 block rounded-xl border border-line px-5 py-2 text-sm font-semibold text-ink hover:bg-hover"
          >
            {t('common.close')}
          </button>
        </div>
      </div>
    )
  }

  const props = buildSummaryProps(summary, itemsQuery.data ?? [], session.name, locale, t)

  return (
    <ThermalReceipt
      {...props}
      zIndexClass="z-[70]"
      // A summary must never kick the cash drawer, and is never auto-printed (user-initiated).
      cashTender={false}
      onPrint={() => window.print()}
      onAction={onClose}
      actionLabel={t('common.close')}
    />
  )
}

/** Maps the server summary onto the shared ThermalReceipt data model (labels pre-formatted). */
function buildSummaryProps(
  s: RegisterSummaryResponse,
  items: ItemSalesResponse[],
  outletName: string,
  locale: string,
  t: ReturnType<typeof useTranslation>['t'],
) {
  const money = (minor: number) => formatMoney(minor, s.currency, locale)
  const dateFmt = new Intl.DateTimeFormat(locale, { dateStyle: 'medium' })
  const timeFmt = new Intl.DateTimeFormat(locale, { timeStyle: 'short' })
  const closed = s.status === 'CLOSED'

  const metaRows: ThermalRow[] = [
    { label: t('register.openedAt'), valueLabel: timeFmt.format(new Date(s.openedAt)) },
    {
      label: closed ? t('register.summaryClosedAt') : t('register.summaryAsOf'),
      valueLabel: timeFmt.format(new Date(s.asOf)),
    },
    // The transaction COUNT is the one non-money numeric — locale-formatted, never money.
    {
      label: t('register.summaryTransactions'),
      valueLabel: new Intl.NumberFormat(locale).format(s.transactionCount),
    },
  ]

  // Revenue breakdown → reconciles to the grand TOTAL: gross − discount − loyalty + service + tax.
  const totalRows: ThermalRow[] = [
    { label: t('register.summaryGross'), valueLabel: money(s.grossSalesMinor) },
  ]
  if (s.discountMinor > 0) {
    totalRows.push({
      label: t('register.summaryDiscount'),
      valueLabel: money(-s.discountMinor),
      isDeduction: true,
    })
  }
  if (s.loyaltyRedeemedMinor > 0) {
    totalRows.push({
      label: t('register.summaryLoyalty'),
      valueLabel: money(-s.loyaltyRedeemedMinor),
      isDeduction: true,
    })
  }
  if (s.serviceChargeMinor > 0) {
    totalRows.push({ label: t('register.summaryService'), valueLabel: money(s.serviceChargeMinor) })
  }
  if (s.taxMinor > 0 || s.usesIllustrativeRules) {
    const taxLabel = s.usesIllustrativeRules
      ? `${t('register.summaryTax')} (${t('register.summaryEstimated')})`
      : t('register.summaryTax')
    totalRows.push({ label: taxLabel, valueLabel: money(s.taxMinor) })
  }

  // Settlement: per-tender GROSS sales (foot to TOTAL) then − Refunds = Net. Zero-value tenders are
  // omitted (they contribute nothing to the foot).
  const paymentRows: ThermalRow[] = []
  for (const td of s.tenders) {
    if (td.salesMinor === 0) continue
    const label = TENDER_LABEL_KEY[td.tenderType]
      ? t(TENDER_LABEL_KEY[td.tenderType] as Parameters<typeof t>[0])
      : td.tenderType
    paymentRows.push({ label, valueLabel: money(td.salesMinor) })
  }
  if (s.refundsMinor > 0) {
    // The minus sign rides the formatted value; ThermalReceipt's payment rows carry no deduction
    // styling, so no isDeduction flag here (it would be a dead prop).
    paymentRows.push({ label: t('register.summaryRefunds'), valueLabel: money(-s.refundsMinor) })
  }
  paymentRows.push({ label: t('register.summaryNet'), valueLabel: money(s.netSalesMinor) })

  // Cash-drawer reconciliation block (a section label, then the drawer figures).
  paymentRows.push({ label: t('register.summaryCashSection'), valueLabel: '' })
  paymentRows.push({ label: t('register.float'), valueLabel: money(s.openingFloatMinor) })
  if (s.expectedCashMinor != null) {
    paymentRows.push({ label: t('register.expected'), valueLabel: money(s.expectedCashMinor) })
  }
  if (s.countedCashMinor != null) {
    paymentRows.push({ label: t('register.counted'), valueLabel: money(s.countedCashMinor) })
  }
  if (s.overShortMinor != null) {
    const os = s.overShortMinor
    const label =
      os > 0
        ? t('register.resultOver')
        : os < 0
          ? t('register.resultShort')
          : t('register.summarySelisih')
    paymentRows.push({ label, valueLabel: money(os) })
  }

  // Per-item sold breakdown (renders "12× Burger … Rp 300.000" between the meta and totals blocks —
  // the ThermalReceipt line-items slot that the Z-report previously left empty). Best-sellers first
  // from the server; revenue is GROSS per item (order-level discount/tax stay in the totals above,
  // so it need not foot exactly to net).
  const lineItems: ThermalLineItem[] = items.map((it) => ({
    qty: it.soldQty,
    name: it.name,
    priceLabel: money(it.revenueMinor),
    modifiers: [],
  }))

  return {
    businessName: outletName,
    title: t('register.summaryTitle'),
    reference: s.sessionId.slice(-8).toUpperCase(),
    tagline: closed ? undefined : t('register.summaryInterim'),
    dateTime: `${dateFmt.format(new Date(s.businessDate))} · ${timeFmt.format(new Date(s.asOf))}`,
    metaRows,
    lineItems,
    totalRows,
    grandTotalLabel: money(s.totalMinor),
    paymentRows,
    footerNote: t('register.summaryFooter'),
    // Prominent banner when the tax rate is an illustrative placeholder (reuses the pending banner).
    isPending: s.usesIllustrativeRules,
    pendingNote: t('register.summaryTaxEstimatedNote'),
  }
}
