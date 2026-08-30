/**
 * StandaloneRegister — mounts the POS RegisterSheet (closing kasir, ADR 0036/0038 daily
 * close) outside the till: the manager "More" sheet's tile, so an owner/manager can close
 * the day without walking through the POS. Hosts the same OutletGate the till uses, and the
 * close verdict chains into the StocktakeSheet (owner request: closing flows into stock
 * opname) without leaving this screen.
 *
 * ADR 0028 guard preserved: closing over an offline device or an unsynced sale queue would
 * understate expected cash, so the same condition that disables the till-menu entry blocks
 * this surface with the same message instead of the sheet.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { ScreenHeader } from '@/components/mobile/ScreenHeader'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { OutletGate } from '@/components/OutletGate'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { StocktakeSheet } from '@/features/stocktake/StocktakeSheet'
import { useOffline } from './offline/useOffline'
import { RegisterSheet } from './RegisterSheet'
import { DailySummary } from './DailySummary'

export function StandaloneRegister({ onClose }: { onClose: () => void }) {
  const { t, i18n } = useTranslation()
  useBackDismiss(onClose)
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const { offline, queuedCount, rejectedCount } = useOffline()
  const [stocktake, setStocktake] = useState(false)
  // The "Cetak ringkasan" print overlay, mirroring Pos.tsx (this surface hosts RegisterSheet too).
  const [summaryOpen, setSummaryOpen] = useState(false)
  const [summarySessionId, setSummarySessionId] = useState<string | null>(null)

  if (!company) return null

  const blocked = offline || queuedCount + rejectedCount > 0

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-paper">
      <ScreenHeader title={t(stocktake ? 'stocktake.title' : 'register.title')} onBack={onClose} />
      {blocked ? (
        <div className="p-4">
          <Card className="p-6 text-center text-sm text-ink-2">
            <TriangleAlert className="mx-auto mb-2 size-5 text-amber" />
            {t('register.disabledOffline')}
          </Card>
        </div>
      ) : (
        <OutletGate company={company} requiredVertical="restaurant">
          {(session) => (
            <>
              {stocktake ? (
                <StocktakeSheet
                  session={session}
                  currency={company.baseCurrency}
                  locale={locale}
                  onClose={onClose}
                />
              ) : (
                <RegisterSheet
                  session={session}
                  currency={company.baseCurrency}
                  locale={locale}
                  onClose={onClose}
                  onContinueToStocktake={() => setStocktake(true)}
                  onPrintSummary={(sessionId) => {
                    setSummarySessionId(sessionId)
                    setSummaryOpen(true)
                  }}
                />
              )}
              {summaryOpen ? (
                <DailySummary
                  session={session}
                  locale={locale}
                  sessionId={summarySessionId}
                  onClose={() => setSummaryOpen(false)}
                />
              ) : null}
            </>
          )}
        </OutletGate>
      )}
    </div>
  )
}
