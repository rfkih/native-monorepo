/**
 * BillPaymentModal — payment step for a guest bill (tab).
 *
 * Same UX as PaymentModal (cash keypad + QRIS/CARD digital), but wired to
 * POST /api/v1/bills/{id}/pay instead of the order checkout flow.
 *
 * The bill's breakdown (live grand total, subtotal, tax, service charge) is
 * shown in the header. The 422 insufficient-stock error shape is handled
 * identically to PaymentModal — parseInsufficientStock is shared.
 *
 * Money rule (rule 8): all amounts are integer minor units.
 * Strings rule (rule 9): all user-facing text is in i18n keys.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { formatMoney } from '@/lib/money'
import { isOutletNotAssigned } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type { PriceBreakdownResponse } from './api'
import { usePayBill, type BillResponse } from './billsApi'
import { parseInsufficientStock } from '@/features/pos-shell/payment/errorKeys'
import { quickChips } from '@/features/pos-shell/payment/quickChips'

type TenderTab = 'CASH' | 'QRIS' | 'CARD'

interface Props {
  session: CompanySession
  bill: BillResponse
  locale: string
  /** When set, this modal pays only the selected lines (split check). */
  lineIds?: string[]
  /** Pre-computed total for the selected lines in minor units (used for split checks). */
  checkTotalMinor?: number
  /** Fresh idempotency key per check-pay attempt — required for split checks. */
  idempotencyKey?: string
  onSuccess: () => void
  onClose: () => void
}

export function BillPaymentModal({
  session,
  bill,
  locale,
  lineIds,
  checkTotalMinor,
  idempotencyKey,
  onSuccess,
  onClose,
}: Props) {
  const { t } = useTranslation()
  const [tender, setTender] = useState<TenderTab>('CASH')

  const currency = bill.currency
  // For a split check use the caller-supplied subtotal; otherwise fall back to the full breakdown.
  const grandTotalMinor =
    checkTotalMinor ??
    bill.breakdown?.grandTotalMinor ??
    bill.lines.reduce((s, l) => s + l.lineTotalMinor, 0)
  // Only show the full breakdown when this is NOT a split check (partial checks have no server breakdown).
  const breakdown = lineIds && lineIds.length > 0 ? null : bill.breakdown

  const tenderOptions: { value: TenderTab; label: string }[] = [
    { value: 'CASH', label: t('pos.payment.tenderCash') },
    { value: 'QRIS', label: t('pos.payment.tenderQris') },
    { value: 'CARD', label: t('pos.payment.tenderCard') },
  ]

  return (
    <div
      className="fixed inset-0 z-[60] grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.payment.title')}
    >
      <Card className="reveal w-full max-w-sm overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <div>
            <h2 className="font-display text-lg font-semibold text-ink">{t('pos.payment.title')}</h2>
            <p className="mt-0.5 text-xs text-ink-3">{bill.guestLabel}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('pos.payment.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* Breakdown */}
        <BillModalBreakdown
          breakdown={breakdown}
          grandTotalMinor={grandTotalMinor}
          currency={currency}
          locale={locale}
        />

        {/* Tender picker */}
        <div className="flex justify-center px-5 py-4">
          <Segmented
            options={tenderOptions}
            value={tender}
            onChange={setTender}
            ariaLabel={t('pos.payment.selectTender')}
          />
        </div>

        {tender === 'CASH' ? (
          <BillCashPanel
            session={session}
            bill={bill}
            grandTotalMinor={grandTotalMinor}
            currency={currency}
            locale={locale}
            lineIds={lineIds}
            idempotencyKey={idempotencyKey}
            onSuccess={onSuccess}
          />
        ) : (
          <BillDigitalPanel
            session={session}
            bill={bill}
            grandTotalMinor={grandTotalMinor}
            currency={currency}
            locale={locale}
            tenderType={tender}
            lineIds={lineIds}
            idempotencyKey={idempotencyKey}
            onSuccess={onSuccess}
            onClose={onClose}
          />
        )}
      </Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Breakdown header
// ---------------------------------------------------------------------------

function BillModalBreakdown({
  breakdown,
  grandTotalMinor,
  currency,
  locale,
}: {
  breakdown: PriceBreakdownResponse | null
  grandTotalMinor: number
  currency: string
  locale: string
}) {
  const { t } = useTranslation()

  if (!breakdown) {
    return (
      <div className="flex items-baseline justify-between border-b border-line px-5 py-3 text-sm text-ink-3">
        <span>{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-medium text-ink">
          {formatMoney(grandTotalMinor, currency, locale)}
        </span>
      </div>
    )
  }

  return (
    <div className="space-y-1.5 border-b border-line px-5 py-3 text-sm">
      <div className="flex items-baseline justify-between text-ink-3">
        <span>{t('pos.subtotal')}</span>
        <span className="tnum font-mono">{formatMoney(breakdown.subtotalMinor, currency, locale)}</span>
      </div>
      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.discount')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}
      <div className="flex items-baseline justify-between text-ink-3">
        <span>{t('pos.serviceCharge')}</span>
        <span className="tnum font-mono">{formatMoney(breakdown.serviceChargeMinor, currency, locale)}</span>
      </div>
      <div className="flex items-baseline justify-between text-ink-3">
        <span>{t('pos.tax')}</span>
        <span className="tnum font-mono">{formatMoney(breakdown.taxMinor, currency, locale)}</span>
      </div>
      <div className="flex items-baseline justify-between border-t border-line pt-1.5 font-medium">
        <span className="text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl text-ink">
          {formatMoney(breakdown.grandTotalMinor, currency, locale)}
        </span>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Cash panel
// ---------------------------------------------------------------------------

function BillCashPanel({
  session,
  bill,
  grandTotalMinor,
  currency,
  locale,
  lineIds,
  idempotencyKey,
  onSuccess,
}: {
  session: CompanySession
  bill: BillResponse
  grandTotalMinor: number
  currency: string
  locale: string
  lineIds?: string[]
  idempotencyKey?: string
  onSuccess: () => void
}) {
  const { t } = useTranslation()
  const payBill = usePayBill(session)
  const [keyStr, setKeyStr] = useState<string>('')

  const tenderedMinor = keyStr === '' ? 0 : parseInt(keyStr, 10)
  const changeMinor = tenderedMinor - grandTotalMinor
  const canPay = tenderedMinor >= grandTotalMinor && !payBill.isPending

  const chips = quickChips(grandTotalMinor, currency)

  function pressDigit(d: string) {
    if (keyStr === '0') return
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

  function pay() {
    if (!canPay) return
    payBill.mutate(
      {
        billId: bill.id,
        payment: { tenderType: 'CASH', tenderedMinor },
        lineIds,
        idempotencyKey,
      },
      {
        onSuccess: () => {
          onSuccess()
        },
      },
    )
  }

  return (
    <div className="px-5 pb-5">
      {/* Quick chips */}
      <div className="mb-3">
        <p className="mb-1.5 text-xs text-ink-3">{t('pos.payment.quickCash')}</p>
        <div className="flex flex-wrap gap-2">
          {chips.map((chip, i) => (
            <button
              key={chip}
              type="button"
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

      {/* Keypad */}
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

      {payBill.isError ? (
        <p className="mb-3 text-xs text-loss" role="alert">
          {(() => {
            if (isOutletNotAssigned(payBill.error)) {
              return t('pos.payment.outletNotAssigned')
            }
            const stockErr = parseInsufficientStock(payBill.error)
            if (stockErr) {
              return t('pos.payment.insufficientStock', {
                itemName: stockErr.itemName,
                available: stockErr.available,
              })
            }
            return (payBill.error as Error).message
          })()}
        </p>
      ) : null}

      {!canPay && tenderedMinor > 0 && tenderedMinor < grandTotalMinor ? (
        <p className="mb-3 text-xs text-amber-2">{t('pos.payment.insufficientTendered')}</p>
      ) : null}

      <Button className="w-full" disabled={!canPay} onClick={pay}>
        {payBill.isPending ? (
          <Spinner />
        ) : (
          t('pos.payment.payAmount', { amount: formatMoney(grandTotalMinor, currency, locale) })
        )}
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

// ---------------------------------------------------------------------------
// Digital panel (QRIS / CARD) — one-step for bills (no PENDING order state)
// ---------------------------------------------------------------------------

function BillDigitalPanel({
  session,
  bill,
  grandTotalMinor,
  currency,
  locale,
  tenderType,
  lineIds,
  idempotencyKey,
  onSuccess,
  onClose,
}: {
  session: CompanySession
  bill: BillResponse
  grandTotalMinor: number
  currency: string
  locale: string
  tenderType: 'QRIS' | 'CARD'
  lineIds?: string[]
  idempotencyKey?: string
  onSuccess: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const payBill = usePayBill(session)

  function pay() {
    payBill.mutate(
      { billId: bill.id, payment: { tenderType }, lineIds, idempotencyKey },
      {
        onSuccess: () => {
          onSuccess()
        },
      },
    )
  }

  return (
    <div className="px-5 pb-5">
      <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3 text-sm text-amber-2">
        <Badge tone="amber" className="mb-2">
          {t('pos.payment.providerPendingBadge')}
        </Badge>
        <p className="mt-1 leading-relaxed">{t('pos.payment.pendingHint')}</p>
      </div>

      {payBill.isError ? (
        <p className="mb-3 text-xs text-loss" role="alert">
          {(() => {
            if (isOutletNotAssigned(payBill.error)) {
              return t('pos.payment.outletNotAssigned')
            }
            const stockErr = parseInsufficientStock(payBill.error)
            if (stockErr) {
              return t('pos.payment.insufficientStock', {
                itemName: stockErr.itemName,
                available: stockErr.available,
              })
            }
            return (payBill.error as Error).message
          })()}
        </p>
      ) : null}

      <Button className="w-full" disabled={payBill.isPending} onClick={pay}>
        {payBill.isPending ? (
          <Spinner />
        ) : (
          t('pos.payment.payAmount', { amount: formatMoney(grandTotalMinor, currency, locale) })
        )}
      </Button>

      <Button
        variant="ghost"
        className="mt-2 w-full text-xs"
        disabled={payBill.isPending}
        onClick={onClose}
      >
        {t('pos.payment.cancel')}
      </Button>
    </div>
  )
}
