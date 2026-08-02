/**
 * BillBreakdown.tsx — extracted VERBATIM from BillDetail.tsx (redesign P2, mechanical move only).
 */
import { useTranslation } from 'react-i18next'
import { formatMoney } from '@/lib/money'
import type { } from '@/lib/session'
import type { PriceBreakdownResponse } from '../api'
import type { } from '../lib/categories'


// ---------------------------------------------------------------------------
// BillBreakdown
// ---------------------------------------------------------------------------

export function BillBreakdown({
  breakdown,
  currency,
  locale,
}: {
  breakdown: PriceBreakdownResponse
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  return (
    <div className="space-y-2 text-sm">
      <div className="flex items-baseline justify-between">
        <span className="text-[11px] text-ink-3">{t('pos.subtotal')}</span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.subtotalMinor, currency, locale)}
        </span>
      </div>
      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between">
          <span className="text-[11px] text-ink-3">{t('pos.discount')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}
      <div className="flex items-baseline justify-between">
        <span className="text-[11px] text-ink-3">{t('pos.serviceCharge')}</span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.serviceChargeMinor, currency, locale)}
        </span>
      </div>
      <div className="flex items-baseline justify-between">
        <span className="text-[11px] text-ink-3">{t('pos.tax')}</span>
        <span className="tnum font-mono text-ink">
          {formatMoney(breakdown.taxMinor, currency, locale)}
        </span>
      </div>
      <div className="flex items-baseline justify-between border-t border-line pt-2">
        <span className="font-semibold text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-[26px] font-bold leading-none text-ink">
          {formatMoney(breakdown.grandTotalMinor, currency, locale)}
        </span>
      </div>
    </div>
  )
}
