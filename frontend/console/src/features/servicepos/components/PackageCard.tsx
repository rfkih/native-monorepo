/**
 * PackageCard.tsx — extracted VERBATIM from ServicePos.tsx (redesign P2, mechanical move only).
 */
import { useTranslation } from 'react-i18next'
import type { } from '@/lib/session'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { } from '@/features/loyalty/api'
import type { VerticalPosConfig } from './../config'
import type {
  CatalogItemResponse,
} from '../api'


// ---------------------------------------------------------------------------
// PackageCard — single-select tile
// ---------------------------------------------------------------------------

export function PackageCard({
  item,
  config,
  locale,
  selected,
  onSelect,
}: {
  item: CatalogItemResponse
  config: VerticalPosConfig
  locale: string
  selected: boolean
  onSelect: () => void
}) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      aria-label={
        selected
          ? t(config.primaryItemLabels.selectedLabelKey, { name: item.name })
          : t(config.primaryItemLabels.selectLabelKey, { name: item.name })
      }
      className={cn(
        'flex flex-col rounded-xl border bg-surface p-4 text-left transition-all duration-200',
        selected
          ? 'border-emerald shadow-md ring-2 ring-emerald/20'
          : 'border-line shadow-sm hover:-translate-y-0.5 hover:border-emerald-line hover:shadow-md active:scale-[0.98]',
      )}
    >
      <span className="line-clamp-2 text-[13px] font-semibold leading-snug text-ink">{item.name}</span>
      {item.description ? (
        <span className="mt-1 line-clamp-2 text-xs text-ink-3">{item.description}</span>
      ) : null}
      <span className="tnum mt-2 font-mono text-[14px] font-semibold text-ink">
        {formatMoney(item.priceMinor, item.currency, locale)}
      </span>
    </button>
  )
}
