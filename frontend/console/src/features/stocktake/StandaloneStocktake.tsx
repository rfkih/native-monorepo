/**
 * StandaloneStocktake — mounts the POS StocktakeSheet outside the till (Native Console
 * Android: the manager "More" sheet's Opname stok tile). A full-screen overlay hosting the
 * same OutletGate the POS uses (ADR 0012 — stock is keyed on a REAL restaurant outlet), so
 * outlet resolution, the no-outlet panel, and the per-vertical coming-soon panel all behave
 * exactly as they do at the till. The sheet itself (system prefill, signed variance, valued
 * shrinkage, stable Idempotency-Key) is reused untouched.
 */
import { useTranslation } from 'react-i18next'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { OutletGate } from '@/components/OutletGate'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { StocktakeSheet } from './StocktakeSheet'

export function StandaloneStocktake({ onClose }: { onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)

  if (!company) return null

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-paper">
      <ScreenHeader title={t('mobile.more.stocktake')} onBack={onClose} />
      <OutletGate company={company} requiredVertical="restaurant">
        {(session) => (
          <StocktakeSheet
            session={session}
            currency={company.baseCurrency}
            locale={locale}
            onClose={onClose}
          />
        )}
      </OutletGate>
    </div>
  )
}
