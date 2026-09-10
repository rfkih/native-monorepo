import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { Drawer } from '@/components/ui/Drawer'
import type { IncomeStatementResponse } from './api'
import { incomeDetailTitle, type IncomeDetailKind } from './incomeDetail'
import { IncomeDetailBody } from './IncomeDetailBody'

export type { IncomeDetailKind } from './incomeDetail'

/**
 * The Income Statement (Laba Rugi) card drill-down on desktop: a right-side drawer opened by clicking
 * a summary card. The CONTENT lives in `IncomeDetailBody` and is shared with the phone bottom sheet
 * (Native Laporan) — this file is only the desktop container: header, close, scroll.
 */
export function IncomeDetailDrawer({
  kind,
  data,
  names,
  currency,
  locale,
  onClose,
}: {
  kind: IncomeDetailKind
  data: IncomeStatementResponse
  names: ReadonlyMap<string, string>
  currency: string
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const title = incomeDetailTitle(kind, t)

  return (
    <Drawer onClose={onClose} ariaLabel={title}>
      <div className="flex items-center justify-between border-b border-line px-5 py-4">
        <div>
          <h2 className="font-display text-lg font-semibold text-ink">{title}</h2>
          <p className="mt-0.5 text-xs text-ink-3">{data.period}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label={t('common.close')}
          className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <X className="size-4" />
        </button>
      </div>
      <div className="flex-1 overflow-y-auto px-5 py-4">
        <IncomeDetailBody kind={kind} data={data} names={names} currency={currency} locale={locale} />
      </div>
    </Drawer>
  )
}
