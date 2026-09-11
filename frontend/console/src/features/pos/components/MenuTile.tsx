/**
 * MenuTile — the catalog card (Native Till Android v2).
 *
 * Redrawn from the phone mockup: a fixed 133px card whose separation comes from a 1px hairline
 * rather than a shadow, and whose selected state is that hairline going ink. Two tiles per row on a
 * 360px phone, so the card has to survive being ~156px wide — hence the 60px media band, the
 * two-line name clamp, and the price on its own mono line.
 *
 * The badge is one slot with three mutually exclusive meanings, in priority order: how many are on
 * the ticket → sold out → running low. Quantity is the only one that fills ink; the stock ones are
 * outline-tinted, so "you added two" never looks like "only two left".
 */
import { useTranslation } from 'react-i18next'
import type { } from '@/lib/session'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { MenuItem } from '../api'
import type { } from '../lib/categories'
import type { } from '@/features/loyalty/api'

/**
 * Image-less tiles show the item's initials, avatar-style — a deliberate placeholder. The old
 * ImageOff glyph read as "picture failed to load" on every photo-less menu (UX audit).
 */
function itemInitials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean)
  // Array.from = code-point-safe first char (an emoji-led name must not become a lone surrogate).
  const letters = words.slice(0, 2).map((w) => (Array.from(w)[0] ?? '').toUpperCase())
  return letters.join('') || '·'
}

const BADGE = 'absolute right-1.5 top-1.5 grid h-5 place-items-center rounded-full px-1.5 text-2xs font-bold'

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
        'reveal relative flex h-[133px] flex-col overflow-hidden rounded-[15px] border bg-surface text-left',
        'transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
        unavailable
          ? 'cursor-not-allowed border-line opacity-55'
          : qty > 0
            ? 'border-emerald active:bg-hover'
            : 'border-line hover:bg-hover active:bg-hover',
      )}
    >
      {/* Media band — 60px whether it holds a photo or the initials, so a mixed catalog still
          lines its names and prices up across the row. */}
      <span className="relative flex h-[60px] w-full shrink-0 items-center justify-center overflow-hidden bg-hover">
        {item.imageUrl ? (
          <img
            src={item.imageUrl}
            alt=""
            loading="lazy"
            className={cn('size-full object-cover', unavailable && 'grayscale')}
          />
        ) : (
          <span
            aria-hidden="true"
            className="select-none text-lg font-extrabold tracking-[.02em] text-ink-3"
          >
            {itemInitials(item.name)}
          </span>
        )}

        {qty > 0 && !unavailable ? (
          <span className={cn(BADGE, 'tnum min-w-5 bg-emerald font-mono text-2xs text-on-emerald')}>
            {qty}
          </span>
        ) : unavailable ? (
          <span className={cn(BADGE, 'bg-ink-100 font-semibold text-ink-2')}>{t('pos.soldOut')}</span>
        ) : isLowStock ? (
          <span className={cn(BADGE, 'bg-tint-warning text-amber ring-1 ring-inset ring-warning-line')}>
            {t('menu.stock.lowStock', { count: item.stockQuantity })}
          </span>
        ) : null}
      </span>

      {/* Name + price */}
      <span className="flex min-w-0 flex-col px-3 pb-3 pt-2.5">
        <span
          className={cn(
            'line-clamp-2 min-h-[34px] text-xs font-semibold leading-[1.35]',
            unavailable ? 'text-ink-3' : 'text-ink',
          )}
        >
          {item.name}
        </span>
        <span
          className={cn(
            'tnum mt-1 font-mono text-sm font-semibold leading-none',
            unavailable ? 'text-ink-3' : 'text-ink-2',
          )}
        >
          {formatMoney(item.priceMinor, item.currency, locale)}
        </span>
      </span>
    </button>
  )
}
