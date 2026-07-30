/**
 * AppliedPromotionChips — the itemized per-rule deduction detail from a live quote's
 * `appliedPromotions` (Phase 3, ADR 0026: automatic rules + line-scope rules + any redeemed coupon —
 * everything EXCEPT the manual discount, which carries no rule and is shown as its own "Discount"
 * line in the breakdown already). Shared by both POS terminals (restaurant + service-POS).
 *
 * Money rule (rule 8): amountMinor is integer minor units, rendered via formatMoney().
 */
import { useTranslation } from 'react-i18next'
import { Percent } from 'lucide-react'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'

export interface AppliedPromotionEntry {
  name: string
  type: string | null
  amountMinor: number
  lineRef: string | null
}

export function AppliedPromotionChips({
  promotions,
  currency,
  locale,
  className,
}: {
  promotions: AppliedPromotionEntry[]
  currency: string
  locale: string
  className?: string
}) {
  const { t } = useTranslation()
  if (promotions.length === 0) return null

  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <span className="text-xs font-semibold text-ink-3">{t('pos.appliedPromotions')}</span>
      <div className="flex flex-wrap gap-1.5">
        {promotions.map((p, i) => (
          <span
            key={`${p.name}-${i}`}
            className="inline-flex items-center gap-1.5 rounded-full bg-emerald-tint px-2.5 py-1 text-xs font-semibold text-emerald-2"
          >
            <Percent className="size-3 shrink-0" aria-hidden />
            {p.name}
            <span className="tnum font-mono">− {formatMoney(p.amountMinor, currency, locale)}</span>
          </span>
        ))}
      </div>
    </div>
  )
}
