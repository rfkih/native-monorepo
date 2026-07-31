/**
 * PaymentModal — Step 2 of the POS checkout flow (ADR 0006, slice 5; extended Phase 4, ADR 0027).
 *
 * Three tenders via a Segmented picker:
 *   CASH  — numeric keypad + quick-cash chips; live change line; Pay fires checkout-with-payment
 *            (POST /api/v1/orders with the payment block). CAPTURED immediately.
 *   QRIS  — two-step: checkout creates PENDING order, then "Mark as paid" calls
 *            POST /api/v1/payments/{id}/capture. Clearly badged "Demo · pending provider".
 *   CARD  — same two-step as QRIS.
 *
 * Phase 2: discountMinor is threaded into checkout; the authoritative grandTotalMinor from the
 * price breakdown drives the charge amount rather than a client-side subtotal.
 *
 * Phase 4 (ADR 0027): a gift-card code may be applied here (GiftCardField) — the loyalty MEMBER
 * attach + points redemption happens upstream on the main POS screen (components/MemberField.tsx)
 * and is only forwarded here read-only via `loyaltyMember`/`loyaltyRedeemPoints`. The tender/keypad
 * math targets `residualDueMinor = grandTotalMinor - giftCardRedeemMinor` — computed CLIENT-SIDE
 * via the identical arithmetic the server's own PriceBreakdownResponse factory uses (no extra
 * quote round-trip needed; see GiftCardField's class doc). When the gift card fully covers the
 * total (residualDueMinor === 0), the tender picker is skipped entirely — see FullCoveragePanel's
 * doc for the wire-shape decision (a zero-amount payment object is still sent, matching the
 * backend's captureZeroResidualInCurrentTx path, which every vertical honours regardless of the
 * chosen tenderType).
 *
 * Money rule (rule 8): all amounts are integer minor units throughout. Displayed via formatMoney().
 * Strings rule (rule 9): every label is an i18n key — no hardcoded user-facing text.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Gift, X } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { AppliedPromotionChips } from '@/components/AppliedPromotionChips'
import { GiftCardField } from '@/components/GiftCardField'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { ApiError, isOutletNotAssigned } from '@/lib/api'
import {
  isGiftCardUnusable,
  isLoyaltyBalanceInsufficient,
  type GiftCardResponse,
  type MemberResponse,
} from '@/features/loyalty/api'
import { OfflineHint } from './offline/OfflineHint'
import { enqueueSale } from './offline/queue'
import type { ProvisionalTotals, SaleQueueRow } from './offline/db'
import type {
  OrderLineInput,
  OrderResponse,
  PaymentResponse,
  PriceBreakdownResponse,
} from './api'
import { useCheckout, useCapturePayment, usePayParked } from './api'

type TenderTab = 'CASH' | 'QRIS' | 'CARD'

/**
 * Detects the checkout-time coupon rejections (ADR 0026: unlike the quote, checkout/pay-parked
 * REJECT a bad/exhausted coupon rather than reporting couponStatus) — 422 coupon-invalid or 409
 * coupon-exhausted (PromotionAdvice.java). Returns the matching i18n key, reusing the same copy the
 * CouponField's inline error uses, or null for any other error shape.
 */
function couponCheckoutErrorKey(err: unknown): 'pos.coupon.invalid' | 'pos.coupon.exhausted' | null {
  if (!(err instanceof ApiError)) return null
  const type = err.problem?.type ?? ''
  if (err.status === 422 && type.includes('coupon-invalid')) return 'pos.coupon.invalid'
  if (err.status === 409 && type.includes('coupon-exhausted')) return 'pos.coupon.exhausted'
  return null
}

/**
 * Detects a 422 insufficient-stock problem+json and returns a structured object
 * with itemName and available so the UI can surface a precise message.
 * Returns null for any other error shape.
 */
function parseInsufficientStock(
  err: unknown,
): { itemName: string; available: number } | null {
  if (!(err instanceof ApiError)) return null
  if (err.status !== 422) return null
  const p = err.problem
  if (!p) return null
  if (typeof p.type !== 'string' || !p.type.includes('insufficient-stock')) return null
  const raw = p as Record<string, unknown>
  if (typeof raw.itemName === 'string' && typeof raw.available === 'number') {
    return { itemName: raw.itemName, available: raw.available }
  }
  return null
}

/**
 * Simple (non-parameterised) checkout-time error keys shared by every tender panel — outlet
 * assignment, coupon, and the Phase 4 (ADR 0027) loyalty/gift-card redemption faults. Stock is
 * handled separately ({@link parseInsufficientStock}) since it needs interpolated params.
 */
function resolveSimpleErrorKey(err: unknown): string | null {
  if (isOutletNotAssigned(err)) return 'pos.payment.outletNotAssigned'
  const couponKey = couponCheckoutErrorKey(err)
  if (couponKey) return couponKey
  if (isLoyaltyBalanceInsufficient(err)) return 'pos.loyalty.member.insufficientBalance'
  if (isGiftCardUnusable(err)) return 'pos.loyalty.giftCard.unusableAtCheckout'
  return null
}

interface Props {
  session: CompanySession
  lines: OrderLineInput[]
  /** Server-authoritative grand total from the quote breakdown; drives the charge amount BEFORE
   * any gift-card tender (a gift card is never a discount — see PriceBreakdownResponse javadoc). */
  grandTotalMinor: number
  /** Optional order-level discount in minor units; passed to checkout. */
  discountMinor: number
  /** Phase 3 (ADR 0026): the committed coupon code (or null); forwarded to checkout/pay-parked. */
  couponCode?: string | null
  /** Phase 4 (ADR 0027): the loyalty member attached upstream on the main POS screen (read-only
   * here — attach/detach lives in MemberField, not this modal). */
  loyaltyMember?: MemberResponse | null
  /** Phase 4 (ADR 0027): the points redemption committed upstream; forwarded verbatim. */
  loyaltyRedeemPoints?: number
  /** Live price breakdown from useQuote — rendered in the modal header. */
  breakdown: PriceBreakdownResponse | null
  currency: string
  locale: string
  onSuccess: (order: OrderResponse, payment: PaymentResponse) => void
  onClose: () => void
  /** Phase 4: when set, the modal finalises a PARKED order rather than creating a new checkout. */
  parkedOrderId?: string | null
  /** Phase 4: order type forwarded to checkout. */
  orderType?: string
  /** Phase 4: table UUID forwarded to checkout (DINE_IN only). */
  tableId?: string | null
  /**
   * Phase 5 (ADR 0028): true when the terminal is currently offline. Forces CASH-only (the tender
   * picker, digital panel, and gift-card redemption are all hidden behind an {@link OfflineHint}),
   * and routes Pay through the offline queue (features/pos/offline) instead of POSTing — `breakdown`
   * is then expected to already be the caller's provisional breakdown (Pos.tsx swaps it in via
   * `toDisplayBreakdown`), so this component never needs to know about ProvisionalBreakdown itself.
   */
  offline?: boolean
  /** Called once the sale is durably enqueued (never after a real POST) instead of onSuccess — the
   * caller renders a provisional receipt. Only meaningful when `offline` is true. */
  onOfflineSuccess?: (row: SaleQueueRow, tenderedMinor: number, changeMinor: number) => void
}

// Quick-cash chip amounts in IDR minor units (= whole rupiah, exponent 0).
// For USD we build chips relative to the total (exact, +$5, +$10).
const IDR_QUICK_CHIPS = [50_000, 100_000] as const

/** Build quick-cash chip options: [exact, ...preset-overs] all as minor units. */
function quickChips(totalMinor: number, currency: string): number[] {
  if (currency === 'IDR') {
    // Exact + preset IDR chips that exceed the total
    return [totalMinor, ...IDR_QUICK_CHIPS.filter((v) => v > totalMinor)]
  }
  // For non-IDR: exact + a couple of round-up multiples of 500 minor units (e.g. USD cents)
  const round500 = Math.ceil(totalMinor / 500) * 500
  const round1000 = Math.ceil(totalMinor / 1000) * 1000
  const chips = [totalMinor]
  if (round500 > totalMinor) chips.push(round500)
  if (round1000 > round500) chips.push(round1000)
  return chips
}

export function PaymentModal({
  session,
  lines,
  grandTotalMinor,
  discountMinor,
  couponCode,
  loyaltyMember,
  loyaltyRedeemPoints,
  breakdown,
  currency,
  locale,
  onSuccess,
  onClose,
  parkedOrderId,
  orderType,
  tableId,
  offline = false,
  onOfflineSuccess,
}: Props) {
  const { t } = useTranslation()
  const [tender, setTender] = useState<TenderTab>('CASH')

  // Phase 4 (ADR 0027): gift-card redemption is entered HERE (unlike the loyalty member, which is
  // attached upstream) — a cashier typically only learns the customer wants to pay by gift card at
  // the till. `residualDueMinor` is the identity the server's PriceBreakdownResponse factory uses;
  // see the class doc for why no second quote round-trip is needed.
  const [giftCard, setGiftCard] = useState<GiftCardResponse | null>(null)
  const [giftCardRedeemMinor, setGiftCardRedeemMinor] = useState(0)
  // Offline (Phase 5, ADR 0028): a gift card is a checkout-time server lookup — unreachable offline,
  // so it is never applied and the residual is simply the (already provisional) grand total.
  const residualDueMinor = offline ? grandTotalMinor : Math.max(0, grandTotalMinor - giftCardRedeemMinor)
  const fullyCoveredByGiftCard = !offline && giftCard != null && residualDueMinor === 0
  // The queue's stored breakdown mirrors the provisional one the caller already rendered via
  // `breakdown` (Pos.tsx swaps in `toDisplayBreakdown(provisionalBreakdown)` when offline) — reusing
  // it here keeps this component free of any dependency on provisionalPricing.ts itself.
  const offlineProvisional: ProvisionalTotals | null =
    offline && breakdown
      ? {
          subtotalMinor: breakdown.subtotalMinor,
          discountMinor: breakdown.discountMinor,
          serviceChargeMinor: breakdown.serviceChargeMinor,
          taxMinor: breakdown.taxMinor,
          grandTotalMinor: breakdown.grandTotalMinor,
          currency: breakdown.currency,
          usesCachedRules: true,
        }
      : null

  const tenderOptions: { value: TenderTab; label: string }[] = [
    { value: 'CASH', label: t('pos.payment.tenderCash') },
    { value: 'QRIS', label: t('pos.payment.tenderQris') },
    { value: 'CARD', label: t('pos.payment.tenderCard') },
  ]

  const loyaltyMemberId = loyaltyMember?.id ?? null
  const effectiveLoyaltyRedeemPoints = loyaltyRedeemPoints ?? 0

  return (
    // Overlay — same backdrop pattern as ChargeSuccess in Pos.tsx
    <div
      className="fixed inset-0 z-40 grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.payment.title')}
    >
      <Card className="reveal w-full max-w-sm overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 className="font-display text-lg font-semibold text-ink">{t('pos.payment.title')}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('pos.payment.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* Inline breakdown — offline: `breakdown` is already the caller's provisional breakdown
            (Pos.tsx), so this renders unchanged with its "estimated" badge doing double duty. */}
        <ModalBreakdown breakdown={breakdown} grandTotalMinor={grandTotalMinor} currency={currency} locale={locale} />

        {/* Gift-card redemption (Phase 4, ADR 0027) — a checkout-time server lookup, unreachable
            offline (Phase 5, ADR 0028). */}
        {offline ? (
          <div className="border-b border-line px-5 py-3 space-y-1.5">
            <OfflineHint text={t('offline.disabled.digitalTender')} />
            <OfflineHint text={t('offline.disabled.giftCard')} />
          </div>
        ) : (
          <div className="border-b border-line px-5 py-3">
            <p className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-ink-3">
              <Gift className="size-3.5 shrink-0" aria-hidden />
              {t('pos.loyalty.giftCard.applyTitle')}
            </p>
            <GiftCardField
              session={session}
              locale={locale}
              card={giftCard}
              onApply={(card, redeem) => {
                setGiftCard(card)
                setGiftCardRedeemMinor(redeem)
              }}
              onClear={() => {
                setGiftCard(null)
                setGiftCardRedeemMinor(0)
              }}
              redeemMinor={giftCardRedeemMinor}
              onRedeemChange={setGiftCardRedeemMinor}
              dueBeforeCardMinor={grandTotalMinor}
            />
          </div>
        )}

        {offline ? (
          // Offline: cash only, no tender picker — CashPanel itself enqueues instead of POSTing.
          <CashPanel
            session={session}
            lines={lines}
            chargeMinor={residualDueMinor}
            discountMinor={discountMinor}
            couponCode={null}
            loyaltyMemberId={loyaltyMemberId}
            loyaltyRedeemPoints={0}
            giftCardId={null}
            giftCardRedeemMinor={0}
            currency={currency}
            locale={locale}
            onSuccess={onSuccess}
            onClose={onClose}
            parkedOrderId={null}
            orderType={orderType}
            tableId={tableId}
            offline
            offlineProvisional={offlineProvisional}
            onOfflineSuccess={onOfflineSuccess}
          />
        ) : fullyCoveredByGiftCard ? (
          <FullCoveragePanel
            session={session}
            lines={lines}
            discountMinor={discountMinor}
            couponCode={couponCode}
            loyaltyMemberId={loyaltyMemberId}
            loyaltyRedeemPoints={effectiveLoyaltyRedeemPoints}
            giftCardId={giftCard!.id}
            giftCardRedeemMinor={giftCardRedeemMinor}
            currency={currency}
            locale={locale}
            onSuccess={onSuccess}
            parkedOrderId={parkedOrderId}
            orderType={orderType}
            tableId={tableId}
          />
        ) : (
          <>
            {/* Tender picker */}
            <div className="flex justify-center px-5 py-4">
              <Segmented
                options={tenderOptions}
                value={tender}
                onChange={setTender}
                ariaLabel={t('pos.payment.selectTender')}
              />
            </div>

            {/* Tender-specific panel */}
            {tender === 'CASH' ? (
              <CashPanel
                session={session}
                lines={lines}
                chargeMinor={residualDueMinor}
                discountMinor={discountMinor}
                couponCode={couponCode}
                loyaltyMemberId={loyaltyMemberId}
                loyaltyRedeemPoints={effectiveLoyaltyRedeemPoints}
                giftCardId={giftCard?.id ?? null}
                giftCardRedeemMinor={giftCardRedeemMinor}
                currency={currency}
                locale={locale}
                onSuccess={onSuccess}
                onClose={onClose}
                parkedOrderId={parkedOrderId}
                orderType={orderType}
                tableId={tableId}
              />
            ) : (
              <DigitalPanel
                session={session}
                lines={lines}
                chargeMinor={residualDueMinor}
                discountMinor={discountMinor}
                couponCode={couponCode}
                loyaltyMemberId={loyaltyMemberId}
                loyaltyRedeemPoints={effectiveLoyaltyRedeemPoints}
                giftCardId={giftCard?.id ?? null}
                giftCardRedeemMinor={giftCardRedeemMinor}
                currency={currency}
                locale={locale}
                tenderType={tender}
                onSuccess={onSuccess}
                onClose={onClose}
                parkedOrderId={parkedOrderId}
                orderType={orderType}
                tableId={tableId}
              />
            )}
          </>
        )}
      </Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Inline breakdown in the modal header
// ---------------------------------------------------------------------------

function ModalBreakdown({
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
    // Fallback: just the total row
    return (
      <div className="flex items-baseline justify-between px-5 py-3 text-sm text-ink-3 border-b border-line">
        <span>{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-medium text-ink">
          {formatMoney(grandTotalMinor, currency, locale)}
        </span>
      </div>
    )
  }

  const illustrative = breakdown.usesIllustrativeRules

  return (
    <div className="border-b border-line px-5 py-3 space-y-1.5 text-sm">
      {/* Subtotal */}
      <div className="flex items-baseline justify-between text-ink-3">
        <span>{t('pos.subtotal')}</span>
        <span className="tnum font-mono">{formatMoney(breakdown.subtotalMinor, currency, locale)}</span>
      </div>

      {/* Discount */}
      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.discount')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      {/* Phase 4 (ADR 0027): loyalty points redeemed — a separate contra-revenue line, never
          folded into the promo-only discount above (matches the wire's discountMinor/
          loyaltyRedeemedMinor split). */}
      {breakdown.loyaltyRedeemedMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.loyalty.redeemedLabel')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.loyaltyRedeemedMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      {/* Service charge */}
      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.serviceCharge')}
          {illustrative ? <InlineEstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.serviceChargeMinor, currency, locale)}</span>
      </div>

      {/* Tax */}
      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.tax')}
          {illustrative ? <InlineEstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.taxMinor, currency, locale)}</span>
      </div>

      {/* Grand total */}
      <div className="flex items-baseline justify-between border-t border-line pt-1.5 mt-0.5 font-medium">
        <span className="text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl text-ink">
          {formatMoney(breakdown.grandTotalMinor, currency, locale)}
        </span>
      </div>

      <AppliedPromotionChips
        promotions={breakdown.appliedPromotions}
        currency={currency}
        locale={locale}
        className="pt-1"
      />
    </div>
  )
}

function InlineEstimatedBadge({ hint }: { hint: string }) {
  const { t } = useTranslation()
  return (
    <span title={hint} aria-label={hint}>
      <Badge tone="amber" className="text-[10px] py-0 px-1.5">
        {t('pos.estimated')}
      </Badge>
    </span>
  )
}

// ---------------------------------------------------------------------------
// Full-coverage panel — the gift card covers the ENTIRE residual (Phase 4, ADR 0027)
// ---------------------------------------------------------------------------

/**
 * Rendered instead of the tender picker + Cash/Digital panels when the applied gift card fully
 * covers `grandTotalMinor` (residualDueMinor === 0). One click completes the order.
 *
 * Wire-shape decision (documented per the task): the backend accepts EITHER omitting `payment`
 * entirely (no payment row is written — a valid path) OR sending a payment object with the
 * residual forced to zero (every vertical's writer special-cases a fully-gift-card-covered sale
 * into an immediate zero-amount CAPTURED record, REGARDLESS of the chosen tenderType —
 * restaurant's `PaymentWriter#captureZeroResidualInCurrentTx`, carwash/barbershop's inline
 * `CarwashPayment.capturedCash(zero, zero, zero)` equivalent). This console ALWAYS sends the
 * payment object (`CASH`, `tenderedMinor: 0`) — the simpler, single wire shape that works
 * identically whether or not the vertical happens to require `payment` (carwash/barbershop's
 * CheckoutRequest.payment is `@NotNull` — restaurant's is optional; sending it unconditionally
 * means this panel never needs a per-vertical branch).
 */
interface FullCoveragePanelProps {
  session: CompanySession
  lines: OrderLineInput[]
  discountMinor: number
  couponCode?: string | null
  loyaltyMemberId: string | null
  loyaltyRedeemPoints: number
  giftCardId: string
  giftCardRedeemMinor: number
  currency: string
  locale: string
  onSuccess: (order: OrderResponse, payment: PaymentResponse) => void
  parkedOrderId?: string | null
  orderType?: string
  tableId?: string | null
}

function FullCoveragePanel({
  session,
  lines,
  discountMinor,
  couponCode,
  loyaltyMemberId,
  loyaltyRedeemPoints,
  giftCardId,
  giftCardRedeemMinor,
  currency,
  locale,
  onSuccess,
  parkedOrderId,
  orderType,
  tableId,
}: FullCoveragePanelProps) {
  const { t } = useTranslation()
  const checkout = useCheckout(session)
  const payParked = usePayParked(session)
  const [idempotencyKey] = useState<string>(() => crypto.randomUUID())
  const isBusy = checkout.isPending || payParked.isPending

  function complete() {
    if (parkedOrderId) {
      payParked.mutate(
        {
          orderId: parkedOrderId,
          payment: { tenderType: 'CASH', tenderedMinor: 0 },
          couponCode,
          loyaltyMemberId,
          loyaltyRedeemPoints,
          giftCardId,
          giftCardRedeemMinor,
        },
        { onSuccess: (res) => res?.payment && onSuccess(res, res.payment) },
      )
    } else {
      checkout.mutate(
        {
          idempotencyKey,
          lines,
          payment: { tenderType: 'CASH', tenderedMinor: 0 },
          discountMinor,
          orderType,
          tableId,
          couponCode,
          loyaltyMemberId,
          loyaltyRedeemPoints,
          giftCardId,
          giftCardRedeemMinor,
        },
        { onSuccess: (res) => res?.payment && onSuccess(res, res.payment) },
      )
    }
  }

  return (
    <div className="px-5 pb-5">
      <div className="mb-4 flex items-center gap-2.5 rounded-xl border border-emerald-line bg-emerald-tint px-4 py-3">
        <Gift className="size-5 shrink-0 text-emerald-2" aria-hidden />
        <div>
          <p className="text-sm font-bold text-emerald-2">{t('pos.loyalty.giftCard.fullyCovered')}</p>
          <p className="tnum mt-0.5 font-mono text-xs text-emerald-2/80">
            {formatMoney(giftCardRedeemMinor, currency, locale)}
          </p>
        </div>
      </div>

      {(checkout.isError || payParked.isError) ? (
        <p className="mb-3 text-xs text-loss" role="alert">
          {(() => {
            const err = checkout.error ?? payParked.error
            const key = resolveSimpleErrorKey(err)
            return key ? t(key) : (err as Error).message
          })()}
        </p>
      ) : null}

      <Button className="w-full" disabled={isBusy} onClick={complete}>
        {isBusy ? <Spinner /> : t('pos.loyalty.giftCard.completeOrder')}
      </Button>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Cash panel
// ---------------------------------------------------------------------------

interface CashPanelProps {
  session: CompanySession
  lines: OrderLineInput[]
  /** The amount to actually authorize — residualDueMinor (grandTotal minus any gift-card tender). */
  chargeMinor: number
  discountMinor: number
  couponCode?: string | null
  loyaltyMemberId: string | null
  loyaltyRedeemPoints: number
  giftCardId: string | null
  giftCardRedeemMinor: number
  currency: string
  locale: string
  onSuccess: (order: OrderResponse, payment: PaymentResponse) => void
  onClose: () => void
  parkedOrderId?: string | null
  orderType?: string
  tableId?: string | null
  /** Phase 5 (ADR 0028): true when the terminal is offline — Pay enqueues instead of POSTing. */
  offline?: boolean
  /** The provisional totals to store on the queued row (required when offline). */
  offlineProvisional?: ProvisionalTotals | null
  onOfflineSuccess?: (row: SaleQueueRow, tenderedMinor: number, changeMinor: number) => void
}

function CashPanel({
  session,
  lines,
  chargeMinor,
  discountMinor,
  couponCode,
  loyaltyMemberId,
  loyaltyRedeemPoints,
  giftCardId,
  giftCardRedeemMinor,
  currency,
  locale,
  onSuccess,
  onClose,
  parkedOrderId,
  orderType,
  tableId,
  offline = false,
  offlineProvisional,
  onOfflineSuccess,
}: CashPanelProps) {
  const { t } = useTranslation()
  const checkout = useCheckout(session)
  const payParked = usePayParked(session)
  const [offlineError, setOfflineError] = useState<string | null>(null)
  const [offlineBusy, setOfflineBusy] = useState(false)

  // One idempotency key per payment ATTEMPT (panel mount), reused across retries — a retry after
  // an ambiguous failure replays the same key and resolves to the same order (never a second
  // charge or coupon redemption). Promotions review W2; the BillDetail pattern.
  const [idempotencyKey] = useState<string>(() => crypto.randomUUID())

  const isBusy = checkout.isPending || payParked.isPending || offlineBusy

  // tenderedMinor is held as an integer; the keypad appends digits to a string, then parsed.
  const [keyStr, setKeyStr] = useState<string>('')

  const tenderedMinor = keyStr === '' ? 0 : parseInt(keyStr, 10)
  const changeMinor = tenderedMinor - chargeMinor
  const canPay = tenderedMinor >= chargeMinor && !isBusy && (!offline || offlineProvisional != null)

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

  /**
   * Offline replacement for checkout.mutate: enqueues the sale (cash-only, no coupon/points/gift
   * card — the server 422s an offline replay carrying any of those) and PERSISTS it before calling
   * back, so a crash/reload between "Pay" and the confirmation can never lose the sale nor show a
   * false success. `loyaltyMemberId` alone IS forwarded — earn attribution is allowed offline.
   */
  async function payOffline() {
    if (!canPay || !offlineProvisional) return
    setOfflineBusy(true)
    setOfflineError(null)
    try {
      const row = await enqueueSale({
        vertical: 'restaurant',
        endpoint: '/api/v1/orders',
        companyId: session.companyId,
        actor: session.actor,
        body: {
          businessId: session.businessId,
          lines,
          payment: { tenderType: 'CASH', tenderedMinor },
          discountMinor: discountMinor && discountMinor > 0 ? discountMinor : null,
          orderType: orderType ?? null,
          tableId: tableId ?? null,
          loyaltyMemberId: loyaltyMemberId || null,
        },
        provisional: offlineProvisional,
      })
      onOfflineSuccess?.(row, tenderedMinor, changeMinor)
    } catch (err) {
      setOfflineError(err instanceof Error ? err.message : String(err))
    } finally {
      setOfflineBusy(false)
    }
  }

  function pay() {
    if (!canPay) return

    if (offline) {
      void payOffline()
      return
    }

    if (parkedOrderId) {
      // Resume path: finalise a PARKED order
      payParked.mutate(
        {
          orderId: parkedOrderId,
          payment: { tenderType: 'CASH', tenderedMinor },
          couponCode,
          loyaltyMemberId,
          loyaltyRedeemPoints,
          giftCardId,
          giftCardRedeemMinor,
        },
        {
          onSuccess: (res) => {
            if (res?.payment) {
              onSuccess(res, res.payment)
            } else {
              onClose()
            }
          },
        },
      )
    } else {
      // Normal checkout path
      checkout.mutate(
        {
          idempotencyKey,
          lines,
          payment: { tenderType: 'CASH', tenderedMinor },
          discountMinor,
          orderType,
          tableId,
          couponCode,
          loyaltyMemberId,
          loyaltyRedeemPoints,
          giftCardId,
          giftCardRedeemMinor,
        },
        {
          onSuccess: (res) => {
            if (res?.payment) {
              onSuccess(res, res.payment)
            } else {
              onClose()
            }
          },
        },
      )
    }
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

      {offlineError ? (
        <p className="mb-3 text-xs text-loss" role="alert">
          {offlineError}
        </p>
      ) : null}

      {!offline && (checkout.isError || payParked.isError) ? (
        <p className="mb-3 text-xs text-loss">
          {(() => {
            const err = checkout.error ?? payParked.error
            if (isOutletNotAssigned(err)) {
              return t('pos.payment.outletNotAssigned')
            }
            const stockErr = parseInsufficientStock(err)
            if (stockErr) {
              return t('pos.payment.insufficientStock', {
                itemName: stockErr.itemName,
                available: stockErr.available,
              })
            }
            const key = resolveSimpleErrorKey(err)
            if (key) {
              return t(key)
            }
            return (err as Error).message
          })()}
        </p>
      ) : null}

      {!canPay && tenderedMinor > 0 && tenderedMinor < chargeMinor ? (
        <p className="mb-3 text-xs text-amber-2">{t('pos.payment.insufficientTendered')}</p>
      ) : null}

      <Button className="w-full" disabled={!canPay} onClick={pay}>
        {isBusy ? <Spinner /> : t('pos.payment.payAmount', { amount: formatMoney(chargeMinor, currency, locale) })}
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
// Digital panel (QRIS / Card) — two-step: checkout → PENDING → capture
// ---------------------------------------------------------------------------

interface DigitalPanelProps {
  session: CompanySession
  lines: OrderLineInput[]
  /** The amount to actually authorize — residualDueMinor (grandTotal minus any gift-card tender). */
  chargeMinor: number
  discountMinor: number
  couponCode?: string | null
  loyaltyMemberId: string | null
  loyaltyRedeemPoints: number
  giftCardId: string | null
  giftCardRedeemMinor: number
  currency: string
  locale: string
  tenderType: 'QRIS' | 'CARD'
  onSuccess: (order: OrderResponse, payment: PaymentResponse) => void
  onClose: () => void
  parkedOrderId?: string | null
  orderType?: string
  tableId?: string | null
}

function DigitalPanel({
  session,
  lines,
  chargeMinor,
  discountMinor,
  couponCode,
  loyaltyMemberId,
  loyaltyRedeemPoints,
  giftCardId,
  giftCardRedeemMinor,
  currency,
  locale,
  tenderType,
  onSuccess,
  onClose,
  parkedOrderId,
  orderType,
  tableId,
}: DigitalPanelProps) {
  const { t } = useTranslation()
  const checkout = useCheckout(session)
  const payParked = usePayParked(session)
  const capture = useCapturePayment(session)

  // One idempotency key per payment ATTEMPT (panel mount), reused across retries (review W2).
  const [idempotencyKey] = useState<string>(() => crypto.randomUUID())

  // After initiation we hold the PENDING order + payment to drive the "Mark as paid" step.
  const [pendingOrder, setPendingOrder] = useState<OrderResponse | null>(null)
  const [pendingPayment, setPendingPayment] = useState<PaymentResponse | null>(null)

  function initiatePayment() {
    if (parkedOrderId) {
      // Resume path: finalise a PARKED order with a digital tender → AWAITING_PAYMENT
      payParked.mutate(
        {
          orderId: parkedOrderId,
          payment: { tenderType },
          couponCode,
          loyaltyMemberId,
          loyaltyRedeemPoints,
          giftCardId,
          giftCardRedeemMinor,
        },
        {
          onSuccess: (res) => {
            if (res?.payment) {
              setPendingOrder(res)
              setPendingPayment(res.payment)
            }
          },
        },
      )
    } else {
      checkout.mutate(
        {
          idempotencyKey,
          lines,
          payment: { tenderType },
          discountMinor,
          orderType,
          tableId,
          couponCode,
          loyaltyMemberId,
          loyaltyRedeemPoints,
          giftCardId,
          giftCardRedeemMinor,
        },
        {
          onSuccess: (res) => {
            if (res?.payment) {
              setPendingOrder(res)
              setPendingPayment(res.payment)
            }
          },
        },
      )
    }
  }

  function confirmPayment() {
    if (!pendingPayment) return
    capture.mutate(pendingPayment.paymentId, {
      onSuccess: (captured) => {
        if (captured && pendingOrder) {
          onSuccess(pendingOrder, captured)
        }
      },
    })
  }

  const isInitiating = checkout.isPending || payParked.isPending

  // Phase 1 — invite cashier to initiate the digital payment
  if (!pendingPayment) {
    return (
      <div className="px-5 pb-5">
        <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3 text-sm text-amber-2">
          <Badge tone="amber" className="mb-2">
            {t('pos.payment.providerPendingBadge')}
          </Badge>
          <p className="mt-1 leading-relaxed">{t('pos.payment.pendingHint')}</p>
        </div>

        {(checkout.isError || payParked.isError) ? (
          <p className="mb-3 text-xs text-loss">
            {(() => {
              const err = checkout.error ?? payParked.error
              if (isOutletNotAssigned(err)) {
                return t('pos.payment.outletNotAssigned')
              }
              const stockErr = parseInsufficientStock(err)
              if (stockErr) {
                return t('pos.payment.insufficientStock', {
                  itemName: stockErr.itemName,
                  available: stockErr.available,
                })
              }
              const key = resolveSimpleErrorKey(err)
              if (key) {
                return t(key)
              }
              return (err as Error).message
            })()}
          </p>
        ) : null}

        <Button className="w-full" disabled={isInitiating} onClick={initiatePayment}>
          {isInitiating ? <Spinner /> : t('pos.payment.payAmount', { amount: formatMoney(chargeMinor, currency, locale) })}
        </Button>
      </div>
    )
  }

  // Phase 2 — PENDING order created; cashier confirms the device showed "paid"
  return (
    <div className="px-5 pb-5">
      {/* Prominent pending badge */}
      <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3">
        <div className="flex items-center gap-2">
          <Badge tone="amber">{t('pos.payment.providerPendingBadge')}</Badge>
        </div>
        <p className="mt-2 text-sm leading-relaxed text-amber-2">{t('pos.payment.pendingHint')}</p>
      </div>

      <div className="mb-4 flex items-baseline justify-between rounded-lg border border-line bg-paper px-4 py-3">
        <span className="text-sm text-ink-3">{t('pos.payment.pending')}</span>
        <span className="tnum font-mono text-lg font-medium text-ink">
          {formatMoney(pendingPayment.amountMinor, currency, locale)}
        </span>
      </div>

      {capture.isError ? (
        <p className="mb-3 text-xs text-loss">{t('pos.payment.errorCapture')}</p>
      ) : null}

      <Button className="w-full" disabled={capture.isPending} onClick={confirmPayment}>
        {capture.isPending ? <Spinner /> : t('pos.payment.markAsPaid')}
      </Button>

      <Button
        variant="ghost"
        className="mt-2 w-full text-xs"
        disabled={capture.isPending}
        onClick={onClose}
      >
        {t('pos.payment.cancel')}
      </Button>
    </div>
  )
}
