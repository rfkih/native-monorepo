/**
 * TenderPickerRow — the shared CASH | QRIS | CARD tender picker row (redesign P3), extracted
 * VERBATIM from the three payment modals (ADR 0006: the three tenders every POS supports).
 */
import { useTranslation } from 'react-i18next'
import { Segmented } from '@/components/ui/Segmented'

export type PosTender = 'CASH' | 'QRIS' | 'CARD'

export function TenderPickerRow({
  value,
  onChange,
}: {
  value: PosTender
  onChange: (t: PosTender) => void
}) {
  const { t } = useTranslation()
  const options: { value: PosTender; label: string }[] = [
    { value: 'CASH', label: t('pos.payment.tenderCash') },
    { value: 'QRIS', label: t('pos.payment.tenderQris') },
    { value: 'CARD', label: t('pos.payment.tenderCard') },
  ]
  return (
    <div className="flex justify-center px-5 py-4">
      <Segmented options={options} value={value} onChange={onChange} ariaLabel={t('pos.payment.selectTender')} />
    </div>
  )
}
