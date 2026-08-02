/**
 * PaymentBreakdown — the price-breakdown header of every POS payment surface (redesign P3).
 * Extracted VERBATIM from PaymentModal/ServicePaymentModal's ModalBreakdown (they were
 * byte-identical) and BillPaymentModal's simpler BillModalBreakdown.
 *
 * variant 'full'   — restaurant walk-in + service tickets: discount, loyalty-redeemed row,
 *                    illustrative-rules badges, applied-promotion chips.
 * variant 'simple' — bill checks: subtotal/discount/service/tax/total only (bills carry no
 *                    coupon/loyalty detail — ADR 0026/0027 scope).
 */
import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/Badge'
import { AppliedPromotionChips } from '@/components/AppliedPromotionChips'
import { formatMoney } from '@/lib/money'
import type { PriceBreakdownResponse } from '@/features/pos/api'

export function PaymentBreakdown({
  breakdown,
  grandTotalMinor,
  currency,
  locale,
  variant = 'full',
}: {
  breakdown: PriceBreakdownResponse | null
  grandTotalMinor: number
  currency: string
  locale: string
  variant?: 'full' | 'simple'
}) {
  const { t } = useTranslation()

  if (!breakdown) {
    // Fallback: just the total row
    return (
      <div className="flex items-baseline justify-between px-5 py-3 text-sm text-ink-3 border-b border-line">
        <span>{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-medium text-ink">
          {formatMoney(grandTotalMinor, currency, locale)}
        </span>
      </div>
    )
  }

  const full = variant === 'full'
  const illustrative = full && breakdown.usesIllustrativeRules

  return (
    <div className="border-b border-line px-5 py-3 space-y-1.5 text-sm">
      {/* Subtotal */}
      <div className="flex items-baseline justify-between text-ink-3">
        <span>{t('pos.subtotal')}</span>
        <span className="tnum font-mono">{formatMoney(breakdown.subtotalMinor, currency, locale)}</span>
      </div>

      {/* Discount */}
      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.discount')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      {/* Phase 4 (ADR 0027): loyalty points redeemed — a separate contra-revenue line, never
          folded into the promo-only discount above (matches the wire's discountMinor/
          loyaltyRedeemedMinor split). */}
      {full && breakdown.loyaltyRedeemedMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.loyalty.redeemedLabel')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.loyaltyRedeemedMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      {/* Service charge */}
      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.serviceCharge')}
          {illustrative ? <InlineEstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.serviceChargeMinor, currency, locale)}</span>
      </div>

      {/* Tax */}
      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.tax')}
          {illustrative ? <InlineEstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.taxMinor, currency, locale)}</span>
      </div>

      {/* Grand total */}
      <div className="flex items-baseline justify-between border-t border-line pt-1.5 mt-0.5 font-medium">
        <span className="text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl text-ink">
          {formatMoney(breakdown.grandTotalMinor, currency, locale)}
        </span>
      </div>

      {full ? (
        <AppliedPromotionChips
          promotions={breakdown.appliedPromotions}
          currency={currency}
          locale={locale}
          className="pt-1"
        />
      ) : null}
    </div>
  )
}

export function InlineEstimatedBadge({ hint }: { hint: string }) {
  const { t } = useTranslation()
  return (
    <span title={hint} aria-label={hint}>
      <Badge tone="amber" className="text-[10px] py-0 px-1.5">
        {t('pos.estimated')}
      </Badge>
    </span>
  )
}
