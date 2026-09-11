/**
 * StocktakeHistorySheet — the opname history as an OVERLAY, for the standalone opname host
 * (StandaloneStocktake opens it over the count in progress). The bodies are `StocktakeHistory`'s
 * — the same list and lines the `/inventory/history` screens draw — inside a `DialogOverlay`
 * (ADR 0075 N3: backdrop, Escape, hardware Back, scroll lock and both animations come from the
 * one primitive). List → lines is local state here: the overlay is an interruption, not a
 * destination, so a tap does not earn a history entry.
 */
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ClipboardList, X } from 'lucide-react'
import { DialogOverlay } from '@/components/ui/Dialog'
import type { CompanySession } from '@/lib/session'
import { useIngredients } from './ingredientApi'
import { useStocktakeHistory } from './ingredientStocktakeApi'
import { StocktakeHistoryLines, StocktakeHistoryList } from './StocktakeHistory'
import { SAFE_AREA_BOTTOM } from '@/lib/safeArea'

const ICON_BUTTON =
  'grid size-9 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald'

export function StocktakeHistorySheet({
  session,
  currency,
  locale,
  onClose,
}: {
  session: CompanySession
  currency: string
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const history = useStocktakeHistory(session, true)
  // The catalog, for the display unit of each line (kg over g) — a cached read, never a gate.
  const ingredients = useIngredients(session)
  const byId = useMemo(
    () => new Map((ingredients.data ?? []).map((i) => [i.id, i])),
    [ingredients.data],
  )
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selected = selectedId
    ? ((history.data ?? []).find((s) => s.id === selectedId) ?? null)
    : null
  const dateTimeFmt = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' })

  return (
    <DialogOverlay
      onClose={onClose}
      ariaLabel={t('stocktake.historyTitle')}
      size="lg"
      className="p-0"
    >
      {(requestClose) => (
        // p-0 = this panel owns its edges, the nav-bar inset included (lib/safeArea).
        <div className="flex max-h-[85dvh] flex-col sm:max-h-[80vh]" style={{ paddingBottom: SAFE_AREA_BOTTOM }}>
          <div className="flex shrink-0 items-center gap-2 border-b border-line px-5 py-4">
            {selected ? (
              <button
                type="button"
                onClick={() => setSelectedId(null)}
                aria-label={t('common.back')}
                className={ICON_BUTTON}
              >
                <ArrowLeft className="size-4" aria-hidden="true" />
              </button>
            ) : (
              <ClipboardList className="size-5 text-ink-2" aria-hidden="true" />
            )}
            <h2 className="min-w-0 flex-1 truncate font-display text-lg font-semibold text-ink">
              {selected
                ? dateTimeFmt.format(new Date(selected.countedAt))
                : t('stocktake.historyTitle')}
            </h2>
            <button
              type="button"
              onClick={requestClose}
              aria-label={t('common.close')}
              className={ICON_BUTTON}
            >
              <X className="size-4" aria-hidden="true" />
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-3">
            {selected ? (
              <StocktakeHistoryLines
                session={session}
                stocktake={selected}
                byId={byId}
                currency={currency}
                locale={locale}
              />
            ) : (
              <StocktakeHistoryList history={history} locale={locale} onOpen={setSelectedId} />
            )}
          </div>
        </div>
      )}
    </DialogOverlay>
  )
}
