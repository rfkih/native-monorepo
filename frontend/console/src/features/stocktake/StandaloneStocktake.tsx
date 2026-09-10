/**
 * StandaloneStocktake — mounts the StocktakeSheet outside the till (Native Console Android: the
 * manager "More" screen's Opname stok tile). A full-screen host holding the same OutletGate the POS
 * uses (ADR 0012 — stock is keyed on a REAL restaurant outlet), so outlet resolution, the no-outlet
 * panel, and the per-vertical coming-soon panel all behave exactly as they do at the till.
 *
 * This host owns the phone chrome and the sheet fills it (`chrome="screen"`). It used to draw a
 * ScreenHeader and then let the sheet draw its own scrim + card on top: the header ended up behind
 * a 40% black scrim, its 44px Back arrow unclickable, competing with the sheet's own X — two
 * chromes for one screen, which ADR 0075 N2 forbids.
 *
 * The header follows the "Native Opname Stok" design: the title over a "company · outlet" line, so
 * the operator can see WHICH outlet's stock is about to be adjusted, and a history button that
 * opens the outlet's past counts (StocktakeHistorySheet — which until now was reachable only from
 * the inventory screen). The button exists only once the gate WOULD pass — an outlet resolved, of
 * the restaurant vertical — because the history sheet needs that outlet's session; a button that
 * is live while the gate shows a panel would tap to nothing.
 *
 * BOTH back paths — the header arrow and the hardware Back — go through the sheet's guarded close
 * (`closeRequestRef`), so an unsubmitted count is never thrown away silently. This host owns the
 * hardware one deliberately: React runs child effects before parent ones, so the sheet registers
 * with the overlay protocol FIRST and this host ends up the registry's topmost — the pop arrives
 * here whatever the sheet wants. Until the sheet mounts (the gate can show a panel instead) there
 * is nothing to lose and Back just closes. While the sheet's discard confirm is up this host's
 * back-dismiss is DISABLED (`onDiscardAskedChange`): the confirm parks its own entry, and a parent
 * that re-parked in the same commit would sit above it in the registry and swallow the Back that
 * should dismiss it — see the note above `useBackDismiss` in StocktakeSheet.
 */
import { useCallback, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { History } from 'lucide-react'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { OutletGate } from '@/components/OutletGate'
import { StocktakeHistorySheet } from '@/features/inventory/StocktakeHistorySheet'
import { useResolvedOutlets } from '@/features/org/useResolvedOutlets'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { StocktakeSheet } from './StocktakeSheet'

export function StandaloneStocktake({ onClose }: { onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const closeRequestRef = useRef<(() => void) | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [discardAsked, setDiscardAsked] = useState(false)

  // The same resolution the gate below runs (shared query cache, idempotent self-heal) — read here
  // for the outlet's NAME, which the gate does not hand down, and to know whether the gate passes.
  const { outlets, effectiveOutletId } = useResolvedOutlets()
  const outlet = outlets.find((o) => o.id === effectiveOutletId)
  // Mirrors OutletGate's own vertical rule, fail-open included (see its comment).
  const gatePasses =
    outlet != null && company != null && (company.vertical ?? 'restaurant') === 'restaurant'

  const requestClose = useCallback(() => {
    const guarded = closeRequestRef.current
    if (guarded) guarded()
    else onClose()
  }, [onClose])

  useBackDismiss(requestClose, !discardAsked)
  useScrollLock()

  if (!company) return null

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-paper">
      <ScreenHeader
        title={t('mobile.more.stocktake')}
        subtitle={
          outlet
            ? t('stocktake.headerSubtitle', { company: company.name, outlet: outlet.name })
            : company.name
        }
        onBack={requestClose}
        trailing={
          gatePasses ? (
            <button
              type="button"
              onClick={() => setHistoryOpen(true)}
              aria-label={t('stocktake.historyTitle')}
              className="grid size-11 place-items-center rounded-xl text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
            >
              <History className="size-[19px]" strokeWidth={1.8} aria-hidden="true" />
            </button>
          ) : null
        }
      />
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
              onDiscardAskedChange={setDiscardAsked}
            />
          )}
        </OutletGate>
      </div>
      {/* Mounted AFTER the sheet, on demand, so it registers as the topmost overlay and the
          hardware Back closes it first. The session is the gate's own shape: the company with the
          resolved outlet as its businessId. */}
      {historyOpen && gatePasses ? (
        <StocktakeHistorySheet
          session={{ ...company, businessId: outlet.id }}
          currency={company.baseCurrency}
          locale={locale}
          onClose={() => setHistoryOpen(false)}
        />
      ) : null}
    </div>
  )
}
