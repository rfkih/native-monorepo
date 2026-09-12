/**
 * QtyKeypad — the 3×4 quantity pad shared by the stock-opname count sheet and the inventory
 * receive / set-quantity sheet. It draws keys only; the draft lives with the caller, which runs
 * every press through `lib/countKeypad`'s `applyCountKey` so a physical keyboard and the pad obey
 * the same rules. The decimal key only EXISTS when a decimal can be saved: a base-unit item
 * (pcs/pack) has no half, so the pad has a hole where the key would be.
 */
import { useTranslation } from 'react-i18next'
import { Delete } from 'lucide-react'
import type { CountKey } from './lib/countKeypad'

export function QtyKeypad({
  fraction,
  separator,
  onKey,
}: {
  /** Whether the item admits a decimal (kg/liter) — decides if the separator key is drawn. */
  fraction: boolean
  /** The operator's locale separator, from `decimalSeparatorOf`. */
  separator: ',' | '.'
  onKey: (key: CountKey) => void
}) {
  const { t } = useTranslation()
  const keys: Array<CountKey | null> = [
    '7',
    '8',
    '9',
    '4',
    '5',
    '6',
    '1',
    '2',
    '3',
    fraction ? separator : null,
    '0',
    'backspace',
  ]
  return (
    <div className="grid shrink-0 grid-cols-3 gap-[7px] px-4">
      {keys.map((key, i) =>
        key == null ? (
          <span key={`hole-${i}`} aria-hidden="true" />
        ) : (
          <button
            key={key}
            type="button"
            // Keep the physical-keyboard focus on the figure: a tap on a pad key must not move it
            // (mousedown is where focus would change; click still fires).
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => onKey(key)}
            aria-label={
              key === 'backspace'
                ? t('inventory.keypad.backspace')
                : key === separator
                  ? t('inventory.keypad.decimal')
                  : t('inventory.keypad.digit', { digit: key })
            }
            className="tnum grid min-h-14 place-items-center rounded-2xl font-mono text-xl font-semibold text-ink transition-[background-color,transform,scale] duration-150 hover:bg-hover active:scale-[.96] active:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald motion-reduce:active:scale-100"
          >
            {key === 'backspace' ? (
              <Delete className="size-5" strokeWidth={1.9} aria-hidden="true" />
            ) : (
              key
            )}
          </button>
        ),
      )}
    </div>
  )
}
