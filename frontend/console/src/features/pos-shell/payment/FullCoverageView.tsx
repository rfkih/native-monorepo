/**
 * FullCoverageView — shown instead of the tender picker when an applied gift card fully covers
 * the total (residualDueMinor === 0; Phase 4, ADR 0027). One click completes the sale. The
 * zero-amount CASH payment wire shape is adapter-owned — see PaymentModal's doc for why every
 * vertical accepts it. Extracted VERBATIM (redesign P3).
 */
import { useTranslation } from 'react-i18next'
import { Gift } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'

export function FullCoverageView({
  giftCardRedeemMinor,
  currency,
  locale,
  busy,
  errorSlot,
  onComplete,
}: {
  giftCardRedeemMinor: number
  currency: string
  locale: string
  busy: boolean
  errorSlot?: React.ReactNode
  onComplete: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="px-5 pb-5">
      <div className="mb-4 flex items-center gap-2.5 rounded-xl border border-emerald-line bg-emerald-tint px-4 py-3">
        <Gift className="size-5 shrink-0 text-emerald-2" aria-hidden />
        <div>
          <p className="text-sm font-bold text-emerald-2">{t('pos.loyalty.giftCard.fullyCovered')}</p>
          <p className="tnum mt-0.5 font-mono text-xs text-emerald-2/80">
            {formatMoney(giftCardRedeemMinor, currency, locale)}
          </p>
        </div>
      </div>

      {errorSlot}

      <Button className="w-full" data-testid="payment-complete" disabled={busy} onClick={onComplete}>
        {busy ? <Spinner /> : t('pos.loyalty.giftCard.completeOrder')}
      </Button>
    </div>
  )
}
