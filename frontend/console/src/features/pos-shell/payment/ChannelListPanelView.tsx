/**
 * ChannelListPanelView — the ONLINE-tender panel (ADR 0036 Phase B3): a grid of active sales
 * channels as large tap targets, then an exact-amount Pay button (no keypad/quick chips, no
 * change — ONLINE is synchronous capture for the exact due amount, mirroring how a full-coverage/
 * exact digital tender renders elsewhere on this surface). Extracted as a shared pos-shell view —
 * stateless beyond the local "which channel is picked" selection, same rule as CashPanelView's
 * keypad string: presentation + local input state only, the MUTATION stays in the adapter.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import { activeChannels, isSelectableChannel, type SalesChannelLike } from './channelPicker'

export function ChannelListPanelView({
  channels,
  chargeMinor,
  currency,
  locale,
  busy,
  errorSlot,
  onPay,
}: {
  /** The full company channel roster (active + inactive) — filtered to active here. */
  channels: SalesChannelLike[]
  /** The amount to authorize — always the exact residual due (no partial tender, no change). */
  chargeMinor: number
  currency: string
  locale: string
  /** True while the adapter's checkout/pay-bill mutation is in flight. */
  busy: boolean
  errorSlot?: React.ReactNode
  onPay: (channelCode: string) => void
}) {
  const { t } = useTranslation()
  const [selected, setSelected] = useState<string | null>(null)

  const options = activeChannels(channels)
  const canPay = isSelectableChannel(options, selected) && !busy

  return (
    <div className="px-5 pb-5" data-testid="channel-panel">
      <p className="mb-1.5 text-xs text-ink-3">{t('pos.payment.selectChannel')}</p>

      {options.length === 0 ? (
        <div className="mb-4 rounded-xl border border-dashed border-line bg-paper px-4 py-6 text-center">
          <p className="text-sm font-medium text-ink">{t('pos.payment.noChannels')}</p>
          <p className="mt-1 text-xs text-ink-3">{t('pos.payment.noChannelsHint')}</p>
        </div>
      ) : (
        <div className="mb-4 grid grid-cols-2 gap-2">
          {options.map((c) => {
            const isSelected = c.code === selected
            return (
              <button
                key={c.id}
                type="button"
                data-testid={`channel-option-${c.code}`}
                aria-pressed={isSelected}
                onClick={() => setSelected(c.code)}
                className={cn(
                  'rounded-xl border px-3 py-3 text-left text-sm font-semibold transition-colors',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
                  isSelected
                    ? 'border-brand-500 bg-brand-50 text-brand-700'
                    : 'border-line bg-surface text-ink-2 hover:bg-hover',
                )}
              >
                {c.name}
              </button>
            )
          })}
        </div>
      )}

      {errorSlot}

      <Button
        className="w-full"
        data-testid="payment-pay"
        disabled={!canPay}
        onClick={() => selected && onPay(selected)}
      >
        {busy ? <Spinner /> : t('pos.payment.payAmount', { amount: formatMoney(chargeMinor, currency, locale) })}
      </Button>
    </div>
  )
}
