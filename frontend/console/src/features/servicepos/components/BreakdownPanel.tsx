/**
 * BreakdownPanel.tsx — extracted VERBATIM from ServicePos.tsx (redesign P2, mechanical move only).
 */
import { useTranslation } from 'react-i18next'
import type { } from '@/lib/session'
import { formatMoney } from '@/lib/money'
import type { } from '@/features/loyalty/api'
import type { } from './../config'
import type {
  PriceBreakdownResponse,
} from '../api'
import { EstimatedBadge } from './ServiceStates'


// ---------------------------------------------------------------------------
// BreakdownPanel — mirrors PaymentModal's ModalBreakdown (not exported, so re-implemented here)
// ---------------------------------------------------------------------------

export function BreakdownPanel({
  breakdown,
  grandTotalMinor,
  currency,
  locale,
}: {
  breakdown: PriceBreakdownResponse | null
  grandTotalMinor: number
  currency: string
  locale: string
}) {
  const { t } = useTranslation()

  if (!breakdown) {
    return (
      <div className="flex items-baseline justify-between border-t border-line pt-3 text-sm text-ink-3">
        <span>{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-medium text-ink">
          {formatMoney(grandTotalMinor, currency, locale)}
        </span>
      </div>
    )
  }

  const illustrative = breakdown.usesIllustrativeRules

  return (
    <div className="space-y-1.5 border-t border-line pt-3 text-sm">
      <div className="flex items-baseline justify-between text-ink-3">
        <span>{t('pos.subtotal')}</span>
        <span className="tnum font-mono">{formatMoney(breakdown.subtotalMinor, currency, locale)}</span>
      </div>

      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.discount')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      {breakdown.loyaltyRedeemedMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.loyalty.redeemedLabel')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.loyaltyRedeemedMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.serviceCharge')}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.serviceChargeMinor, currency, locale)}</span>
      </div>

      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.tax')}
          {illustrative ? <EstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.taxMinor, currency, locale)}</span>
      </div>

      <div className="flex items-baseline justify-between border-t border-line pt-1.5 mt-0.5 font-medium">
        <span className="text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl text-ink">
          {formatMoney(breakdown.grandTotalMinor, currency, locale)}
        </span>
      </div>
    </div>
  )
}
