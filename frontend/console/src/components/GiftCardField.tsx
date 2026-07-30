/**
 * GiftCardField — shared payment-modal gift-card redemption control (Phase 4, ADR 0027). Used by
 * BOTH features/pos/PaymentModal.tsx and features/servicepos/ServicePaymentModal.tsx — lives in
 * components/, mirroring CouponField/MemberField's shared placement.
 *
 * Flow: enter a code → "Apply" looks it up (GET /api/v1/loyalty/gift-cards/{code}), and on an
 * ACTIVE card auto-applies the default amount `min(balance, dueBeforeCardMinor)` (the task's
 * "apply amount (default min(balance, total))"). The applied amount stays editable (clamped to the
 * same ceiling) so a cashier can redeem less than the full available/owed amount. A non-ACTIVE
 * card (DEPLETED/EXPIRED/VOID) is looked up successfully but flagged unusable and never applied —
 * the checkout-time 409 gift-card-unusable is the authoritative fail-safe for a state change
 * between lookup and charge.
 *
 * `dueBeforeCardMinor` is the grand total BEFORE this gift card (server `grandTotalMinor` already
 * excludes any gift-card tender by design — PriceBreakdownResponse javadoc) — the caller passes it
 * so this field never needs its own quote round-trip; residualDueMinor is simply
 * `dueBeforeCardMinor - redeemMinor`, the identical arithmetic the server's own
 * `PriceBreakdownResponse` factory performs.
 *
 * Money rule (rule 8): amounts are integer minor units, formatted via formatMoney().
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CreditCard, TriangleAlert, X } from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import { isGiftCardNotFound, useLookupGiftCard, type GiftCardResponse } from '@/features/loyalty/api'

interface Props {
  session: CompanySession
  locale: string
  /** The applied card, or null. Controlled — the parent threads `card.id` into checkout as
   * `giftCardId` and `redeemMinor` as `giftCardRedeemMinor`. */
  card: GiftCardResponse | null
  onApply: (card: GiftCardResponse, redeemMinor: number) => void
  onClear: () => void
  redeemMinor: number
  onRedeemChange: (minor: number) => void
  /** The grand total BEFORE this gift card (see class doc) — the redemption ceiling. */
  dueBeforeCardMinor: number
  disabled?: boolean
  className?: string
}

export function GiftCardField({
  session,
  locale,
  card,
  onApply,
  onClear,
  redeemMinor,
  onRedeemChange,
  dueBeforeCardMinor,
  disabled,
  className,
}: Props) {
  const { t } = useTranslation()
  const [code, setCode] = useState('')
  const [notFound, setNotFound] = useState(false)
  const [genericError, setGenericError] = useState<string | null>(null)

  const lookup = useLookupGiftCard(session)

  function submit() {
    const trimmed = code.trim()
    if (!trimmed || disabled) return
    setNotFound(false)
    setGenericError(null)
    lookup.mutate(trimmed, {
      onSuccess: (found) => {
        if (!found) return
        setCode('')
        if (found.state !== 'ACTIVE') return // shown as unusable below; never auto-applied
        const defaultAmount = Math.max(0, Math.min(found.balanceMinor, dueBeforeCardMinor))
        onApply(found, defaultAmount)
      },
      onError: (err) => {
        if (isGiftCardNotFound(err)) {
          setNotFound(true)
        } else {
          setGenericError(t('pos.loyalty.giftCard.lookupError'))
        }
      },
    })
  }

  function clampRedeem(raw: number) {
    if (!card || isNaN(raw) || raw < 0) return 0
    return Math.min(Math.round(raw), Math.max(0, Math.min(card.balanceMinor, dueBeforeCardMinor)))
  }

  const lookedUpUnusable = lookup.data && lookup.data.state !== 'ACTIVE' ? lookup.data : null

  if (!card) {
    return (
      <div className={cn('flex flex-col gap-1.5', className)}>
        <div className="flex items-center gap-2">
          <input
            type="text"
            value={code}
            disabled={disabled}
            onChange={(e) => {
              setCode(e.target.value.toUpperCase())
              setNotFound(false)
              setGenericError(null)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                submit()
              }
            }}
            placeholder={t('pos.loyalty.giftCard.codePlaceholder')}
            aria-label={t('pos.loyalty.giftCard.codeLabel')}
            className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-surface px-3 font-mono text-[13px] uppercase tracking-wide text-ink placeholder:font-sans placeholder:normal-case placeholder:tracking-normal placeholder:text-ink-3/70 transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
          />
          <button
            type="button"
            onClick={submit}
            disabled={disabled || code.trim() === '' || lookup.isPending}
            className="h-10 shrink-0 rounded-xl border border-emerald-line bg-emerald-tint px-3.5 text-[13px] font-bold text-emerald-2 transition-colors hover:bg-brand-100/60 disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            {lookup.isPending ? <Spinner className="size-3.5" /> : t('pos.loyalty.giftCard.apply')}
          </button>
        </div>
        {notFound ? (
          <p className="text-xs text-loss" role="alert">
            {t('pos.loyalty.giftCard.notFound')}
          </p>
        ) : lookedUpUnusable ? (
          <p className="flex items-center gap-1.5 text-xs text-loss" role="alert">
            <TriangleAlert className="size-3.5 shrink-0" aria-hidden />
            {t('pos.loyalty.giftCard.unusable', { state: t(`pos.loyalty.giftCard.state.${lookedUpUnusable.state}`) })}
          </p>
        ) : genericError ? (
          <p className="text-xs text-loss" role="alert">
            {genericError}
          </p>
        ) : null}
      </div>
    )
  }

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <div className="flex items-center gap-2">
        <Badge tone="emerald" className="flex-1 justify-between px-3 py-1.5 text-[13px]">
          <span className="flex min-w-0 items-center gap-1.5">
            <CreditCard className="size-3.5 shrink-0" aria-hidden />
            <span className="truncate font-mono font-semibold">{card.code}</span>
          </span>
          <span className="tnum shrink-0 font-mono text-[11px] font-semibold">
            {t('pos.loyalty.giftCard.balanceBadge', { amount: formatMoney(card.balanceMinor, card.currency, locale) })}
          </span>
        </Badge>
        <button
          type="button"
          onClick={onClear}
          disabled={disabled}
          aria-label={t('pos.loyalty.giftCard.clear')}
          className="grid size-9 shrink-0 place-items-center rounded-xl text-ink-3 transition-colors hover:bg-hover hover:text-loss disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <X className="size-4" />
        </button>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor="gift-card-redeem" className="shrink-0 text-xs font-medium text-ink-2">
          {t('pos.loyalty.giftCard.applyLabel')}
        </label>
        <input
          id="gift-card-redeem"
          type="number"
          inputMode="numeric"
          min={0}
          max={Math.min(card.balanceMinor, dueBeforeCardMinor)}
          step={1}
          value={redeemMinor === 0 ? '' : String(redeemMinor)}
          disabled={disabled}
          onChange={(e) => onRedeemChange(clampRedeem(Number(e.target.value)))}
          placeholder="0"
          className="h-9 w-32 rounded-xl border border-line bg-surface px-2.5 text-right font-mono text-[13px] text-ink transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/15"
        />
        <span className="tnum text-[11px] font-semibold text-emerald-2">
          − {formatMoney(redeemMinor, card.currency, locale)}
        </span>
      </div>
    </div>
  )
}
