/**
 * AddonChip.tsx — extracted VERBATIM from ServicePos.tsx (redesign P2, mechanical move only).
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
// AddonChip — toggle multi-select
// ---------------------------------------------------------------------------

export function AddonChip({
  item,
  config,
  locale,
  selected,
  onToggle,
}: {
  item: CatalogItemResponse
  config: VerticalPosConfig
  locale: string
  selected: boolean
  onToggle: () => void
}) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={selected}
      aria-label={
        selected
          ? t(`${config.i18nNs}.addonSelectedLabel`, { name: item.name })
          : t(`${config.i18nNs}.selectAddonLabel`, { name: item.name })
      }
      className={cn(
        'flex h-10 shrink-0 items-center gap-2 rounded-full border-[1.5px] px-4 text-[13px] font-semibold transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
        selected
          ? 'border-emerald bg-emerald text-on-emerald'
          : 'border-line bg-surface text-ink-2 hover:border-emerald-line hover:bg-emerald-tint',
      )}
    >
      <span>{item.name}</span>
      <span className="tnum font-mono text-[11px] opacity-80">
        {formatMoney(item.priceMinor, item.currency, locale)}
      </span>
    </button>
  )
}
