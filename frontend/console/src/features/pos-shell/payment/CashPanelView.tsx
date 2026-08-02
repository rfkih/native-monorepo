/**
 * CashPanelView — the shared cash-tender panel (redesign P3): quick-cash chips, tendered
 * display, 3×4 keypad, live change line, insufficient-tendered warning, Pay button. Extracted
 * VERBATIM from the three payment modals' CashPanels (their markup was byte-identical).
 *
 * pos-shell rule: presentation + local input state only. The MUTATION (checkout / pay-bill /
 * ticket-checkout / offline enqueue) stays in the calling adapter: `onPay(tenderedMinor)` fires
 * it, `busy`/`errorSlot` reflect it. The keypad string is the only state owned here.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import { quickChips } from './quickChips'

export function CashPanelView({
  chargeMinor,
  currency,
  locale,
  busy,
  payDisabled = false,
  errorSlot,
  initialTenderedMinor,
  onPay,
}: {
  /** The amount to authorize — residualDueMinor (grand total minus any gift-card tender). */
  chargeMinor: number
  currency: string
  locale: string
  /** True while the adapter's mutation (or offline enqueue) is in flight. */
  busy: boolean
  /** Extra adapter-side gate (e.g. offline without a provisional breakdown). */
  payDisabled?: boolean
  /** Adapter-rendered error line(s) — CheckoutErrorText or an offline error. */
  errorSlot?: React.ReactNode
  /** P4: pre-fill the keypad (exact-tendered default) so Pay is one tap; chips/keypad override. */
  initialTenderedMinor?: number
  onPay: (tenderedMinor: number, changeMinor: number) => void
}) {
  const { t } = useTranslation()

  // tenderedMinor is held as an integer; the keypad appends digits to a string, then parsed.
  // P4: mounts with the exact amount pre-filled (when given) — the common cash sale is one tap.
  const [keyStr, setKeyStr] = useState<string>(() =>
    initialTenderedMinor && initialTenderedMinor > 0 ? String(initialTenderedMinor) : '',
  )
  const tenderedMinor = keyStr === '' ? 0 : parseInt(keyStr, 10)
  const changeMinor = tenderedMinor - chargeMinor
  const canPay = tenderedMinor >= chargeMinor && !busy && !payDisabled

  const chips = quickChips(chargeMinor, currency)

  function pressDigit(d: string) {
    if (keyStr === '0') return // prevent leading zero accumulation
    setKeyStr((s) => (s.length >= 12 ? s : s + d))
  }
  function pressBackspace() {
    setKeyStr((s) => s.slice(0, -1))
  }
  function pressClear() {
    setKeyStr('')
  }
  function setChip(minor: number) {
    setKeyStr(String(minor))
  }

  return (
    <div className="px-5 pb-5">
      {/* Quick-cash chips */}
      <div className="mb-3">
        <p className="mb-1.5 text-xs text-ink-3">{t('pos.payment.quickCash')}</p>
        <div className="flex flex-wrap gap-2">
          {chips.map((chip, i) => (
            <button
              key={chip}
              type="button"
              data-testid={i === 0 ? 'payment-exact' : undefined}
              onClick={() => setChip(chip)}
              className={[
                'rounded-xl border px-3 py-1.5 text-xs font-semibold transition-colors',
                tenderedMinor === chip
                  ? 'border-brand-500 bg-brand-50 text-brand-700'
                  : 'border-line bg-surface text-ink-2 hover:bg-hover',
              ].join(' ')}
            >
              {i === 0 ? t('pos.payment.exactAmount') : formatMoney(chip, currency, locale)}
            </button>
          ))}
        </div>
      </div>

      {/* Tendered display */}
      <div className="mb-3 rounded-lg border border-line bg-paper px-4 py-3 text-right">
        <p className="text-xs text-ink-3">{t('pos.payment.tendered')}</p>
        <p className="tnum font-mono text-2xl font-medium text-ink">
          {tenderedMinor > 0 ? formatMoney(tenderedMinor, currency, locale) : '—'}
        </p>
      </div>

      {/* Numeric keypad (3×4 grid) */}
      <div className="mb-3 grid grid-cols-3 gap-1.5">
        {['7', '8', '9', '4', '5', '6', '1', '2', '3'].map((d) => (
          <KeypadButton key={d} label={d} onClick={() => pressDigit(d)} />
        ))}
        <KeypadButton label="C" onClick={pressClear} />
        <KeypadButton label="0" onClick={() => pressDigit('0')} />
        <KeypadButton label="⌫" onClick={pressBackspace} />
      </div>

      {/* Change line */}
      <div className="mb-4 flex items-baseline justify-between rounded-xl bg-tint-profit px-4 py-2.5">
        <span className="text-sm font-semibold text-brand-700">{t('pos.payment.change')}</span>
        <span className="tnum font-mono text-lg font-bold text-brand-700">
          {changeMinor >= 0 ? formatMoney(changeMinor, currency, locale) : '—'}
        </span>
      </div>

      {errorSlot}

      {!canPay && tenderedMinor > 0 && tenderedMinor < chargeMinor ? (
        <p className="mb-3 text-xs text-amber-2">{t('pos.payment.insufficientTendered')}</p>
      ) : null}

      <Button
        className="w-full"
        data-testid="payment-pay"
        disabled={!canPay}
        onClick={() => onPay(tenderedMinor, changeMinor)}
      >
        {busy ? <Spinner /> : t('pos.payment.payAmount', { amount: formatMoney(chargeMinor, currency, locale) })}
      </Button>
    </div>
  )
}

function KeypadButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="tnum flex h-11 items-center justify-center rounded-xl border border-line bg-surface font-mono text-base font-semibold text-ink transition-colors hover:bg-hover active:bg-brand-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
    >
      {label}
    </button>
  )
}
