/**
 * MenuTile.tsx — extracted VERBATIM from Pos.tsx (redesign P2, mechanical move only).
 * Markup and behavior unchanged; closures became props where needed.
 */
import { useTranslation } from 'react-i18next'
import {
  ImageOff,
} from 'lucide-react'
import type { } from '@/lib/session'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { MenuItem } from '../api'
import type { } from '../lib/categories'
import type { } from '@/features/loyalty/api'


// ---------------------------------------------------------------------------
// MenuTile — 3b design tile
// ---------------------------------------------------------------------------

export function MenuTile({
  item,
  qty,
  locale,
  index,
  onAdd,
}: {
  item: MenuItem
  qty: number
  locale: string
  index: number
  onAdd: () => void
}) {
  const { t } = useTranslation()
  const stockSoldOut = item.stockQuantity != null && item.stockQuantity <= 0
  const unavailable = !item.available || stockSoldOut
  const isLowStock = item.stockQuantity != null && item.stockQuantity > 0 && item.stockQuantity <= 5
  const delayMs = Math.min(index, 12) * 40
  const hasImage = !!item.imageUrl

  return (
    <button
      type="button"
      onClick={unavailable ? undefined : onAdd}
      disabled={unavailable}
      aria-label={
        unavailable
          ? t('pos.soldOutLabel', { name: item.name })
          : t('pos.addItem', { name: item.name })
      }
      aria-disabled={unavailable}
      style={{ animationDelay: `${delayMs}ms` }}
      className={cn(
        'reveal relative flex flex-col overflow-hidden rounded-xl bg-surface text-left transition-all duration-200',
        unavailable
          ? 'cursor-not-allowed opacity-55 shadow-sm'
          : qty > 0
            ? 'shadow-md ring-2 ring-emerald/25 hover:-translate-y-0.5 hover:shadow-lg active:scale-[0.98]'
            : 'shadow-sm hover:-translate-y-0.5 hover:shadow-md active:scale-[0.98]',
      )}
    >
      {/* Image area (104px) or placeholder */}
      {hasImage ? (
        <div className="relative h-[104px] w-full overflow-hidden bg-ink-50">
          <img
            src={item.imageUrl!}
            alt={item.name}
            loading="lazy"
            className={cn(
              'h-full w-full object-cover transition-transform duration-300',
              !unavailable && 'group-hover:scale-[1.04]',
              unavailable && 'grayscale',
            )}
          />
          {/* Low stock / sold-out badge */}
          {isLowStock && !unavailable ? (
            <span className="absolute right-2 top-2 z-10 flex h-6 items-center rounded-full bg-tint-warning px-2.5 text-[11px] font-bold text-amber-2">
              {t('menu.stock.lowStock', { count: item.stockQuantity })}
            </span>
          ) : unavailable ? (
            <span className="absolute right-2 top-2 z-10 flex h-6 items-center rounded-full bg-ink-50 px-2.5 text-[11px] font-semibold text-ink-3">
              {t('pos.soldOut')}
            </span>
          ) : null}
          {/* Qty badge */}
          {!unavailable && qty > 0 ? (
            <span className="tnum absolute right-2 top-2 grid h-6 min-w-6 place-items-center rounded-full bg-emerald px-1.5 font-mono text-xs font-bold text-on-emerald shadow-sm">
              {qty}
            </span>
          ) : null}
        </div>
      ) : (
        /* Compact text tile for image-less items (72px) */
        <div className="relative flex h-[72px] w-full items-center justify-center bg-ink-50">
          <ImageOff className="size-5 text-ink-200" aria-hidden="true" />
          {isLowStock && !unavailable ? (
            <span className="absolute right-2 top-2 flex h-6 items-center rounded-full bg-tint-warning px-2.5 text-[11px] font-bold text-amber-2">
              {t('menu.stock.lowStock', { count: item.stockQuantity })}
            </span>
          ) : unavailable ? (
            <span className="absolute right-2 top-2 flex h-6 items-center rounded-full bg-ink-50 px-2.5 text-[11px] font-semibold text-ink-3">
              {t('pos.soldOut')}
            </span>
          ) : null}
          {!unavailable && qty > 0 ? (
            <span className="tnum absolute right-2 top-2 grid h-6 min-w-6 place-items-center rounded-full bg-emerald px-1.5 font-mono text-xs font-bold text-on-emerald shadow-sm">
              {qty}
            </span>
          ) : null}
        </div>
      )}

      {/* Content */}
      <div className="flex flex-col px-3 pb-3 pt-2.5">
        <span
          className={cn(
            'line-clamp-2 text-[13px] font-semibold leading-snug',
            unavailable ? 'text-ink-3' : 'text-ink',
          )}
        >
          {item.name}
        </span>
        <div className="mt-1.5 flex items-end justify-between">
          <span
            className={cn(
              'tnum font-mono text-[14px] font-semibold',
              unavailable ? 'text-ink-3/50' : 'text-ink',
            )}
          >
            {formatMoney(item.priceMinor, item.currency, locale)}
          </span>
        </div>
      </div>
    </button>
  )
}
