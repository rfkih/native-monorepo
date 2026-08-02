/**
 * TenderPickerRow — the shared CASH | QRIS | CARD tender picker row (redesign P3), extracted
 * VERBATIM from the three payment modals (ADR 0006: the three tenders every POS supports), plus
 * an opt-in ONLINE (platform-channel) tender (ADR 0036 Phase B3) — restaurant orders/bills only
 * for now; carwash/barbershop tickets (features/servicepos) have no channel-checkout contract
 * yet, so ServicePaymentModal never sets `showOnline` and the option simply isn't offered there.
 */
import { useTranslation } from 'react-i18next'
import { Segmented } from '@/components/ui/Segmented'

export type PosTender = 'CASH' | 'QRIS' | 'CARD' | 'ONLINE'

export function TenderPickerRow({
  value,
  onChange,
  showOnline = false,
  onlineDisabledReason,
}: {
  value: PosTender
  onChange: (t: PosTender) => void
  /** ADR 0036 Phase B3: include the ONLINE tender in the picker. Default false. */
  showOnline?: boolean
  /**
   * When set (and `showOnline`), the ONLINE option renders disabled with this (already-translated)
   * reason as its tooltip and cannot be selected — an offline terminal (ADR 0028: a platform order
   * needs a live round-trip, it can never be rung offline) or an active gift-card/loyalty-points
   * redemption the server rejects alongside ONLINE (ADR 0036: "ONLINE ... carries no gift-card/
   * loyalty legs"). `undefined`/`null` = ONLINE is selectable.
   */
  onlineDisabledReason?: string | null
}) {
  const { t } = useTranslation()
  const options: { value: PosTender; label: string; disabled?: boolean; title?: string }[] = [
    { value: 'CASH', label: t('pos.payment.tenderCash') },
    { value: 'QRIS', label: t('pos.payment.tenderQris') },
    { value: 'CARD', label: t('pos.payment.tenderCard') },
  ]
  if (showOnline) {
    options.push({
      value: 'ONLINE',
      label: t('pos.payment.tenderOnline'),
      disabled: onlineDisabledReason != null,
      title: onlineDisabledReason ?? undefined,
    })
  }
  return (
    <div className="flex justify-center px-5 py-4">
      <Segmented options={options} value={value} onChange={onChange} ariaLabel={t('pos.payment.selectTender')} />
    </div>
  )
}
