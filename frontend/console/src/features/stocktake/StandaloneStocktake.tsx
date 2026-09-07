/**
 * StandaloneStocktake — mounts the StocktakeSheet outside the till (Native Console Android: the
 * manager "More" sheet's Opname stok tile). A full-screen host holding the same OutletGate the POS
 * uses (ADR 0012 — stock is keyed on a REAL restaurant outlet), so outlet resolution, the no-outlet
 * panel, and the per-vertical coming-soon panel all behave exactly as they do at the till.
 *
 * This host owns the phone chrome and the sheet fills it (`chrome="screen"`). It used to draw a
 * ScreenHeader and then let the sheet draw its own scrim + card on top: the header ended up behind
 * a 40% black scrim, its 44px Back arrow unclickable, competing with the sheet's own X — two
 * chromes for one screen, which ADR 0075 N2 forbids.
 *
 * BOTH back paths — the header arrow and the hardware Back — go through the sheet's guarded close
 * (`closeRequestRef`), so an unsubmitted count is never thrown away silently. This host owns the
 * hardware one deliberately: React runs child effects before parent ones, so the sheet registers
 * with the overlay protocol FIRST and this host ends up the registry's topmost — the pop arrives
 * here whatever the sheet wants. `onStay` re-parks the entry that press consumed. Until the sheet
 * mounts (the gate can show a panel instead) there is nothing to lose and Back just closes.
 */
import { useCallback, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { OutletGate } from '@/components/OutletGate'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { StocktakeSheet } from './StocktakeSheet'

export function StandaloneStocktake({ onClose }: { onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const closeRequestRef = useRef<((onStay?: () => void) => void) | null>(null)
  const [backArmKey, setBackArmKey] = useState(0)
  const rearmBack = useCallback(() => setBackArmKey((k) => k + 1), [setBackArmKey])

  const requestClose = useCallback(
    (onStay?: () => void) => {
      const guarded = closeRequestRef.current
      if (guarded) guarded(onStay)
      else onClose()
    },
    [onClose],
  )

  useBackDismiss(() => requestClose(rearmBack), true, backArmKey)
  useScrollLock()

  if (!company) return null

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-paper">
      <ScreenHeader title={t('mobile.more.stocktake')} onBack={() => requestClose()} />
      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">
        <OutletGate company={company} requiredVertical="restaurant">
          {(session) => (
            <StocktakeSheet
              session={session}
              currency={company.baseCurrency}
              locale={locale}
              onClose={onClose}
              chrome="screen"
              closeRequestRef={closeRequestRef}
            />
          )}
        </OutletGate>
      </div>
    </div>
  )
}
