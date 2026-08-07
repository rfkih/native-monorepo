/**
 * PaymentModal — Step 2 of the restaurant POS checkout flow (ADR 0006, slice 5; extended
 * Phase 4 ADR 0027, Phase 5 ADR 0028, Phase 6 ADR 0029).
 *
 * Redesign P3: this file is now the restaurant ADAPTER over the shared payment surface in
 * features/pos-shell/payment (frame, breakdown, cash/digital/full-coverage views — markup
 * extracted verbatim from here). Everything behavioral stays in THIS file: the checkout /
 * pay-parked / capture mutations, the per-attempt idempotency key (usePaymentAttempt — one key
 * per panel mount, reused across retries), the offline enqueue (durably persisted BEFORE the
 * success callback), the gift-card residual math, and the customer-display transitions.
 *
 *   CASH  — keypad + quick chips; checkout-with-payment → CAPTURED immediately.
 *   QRIS/CARD — two-step: checkout creates PENDING, "Mark as paid" captures.
 *   Gift card fully covering the total → one-click zero-amount CASH payment (see
 *   FullCoverageView + the wire-shape doc below).
 *
 * Money rule (rule 8): integer minor units throughout. Strings rule (rule 9): i18n keys only.
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Gift } from 'lucide-react'
import { GiftCardField } from '@/components/GiftCardField'
import type { CompanySession } from '@/lib/session'
import type { GiftCardResponse, MemberResponse } from '@/features/loyalty/api'
import { PaymentSurfaceFrame } from '@/features/pos-shell/payment/PaymentSurfaceFrame'
import { PaymentBreakdown } from '@/features/pos-shell/payment/PaymentBreakdown'
import { TenderPickerRow, type PosTender } from '@/features/pos-shell/payment/TenderPickerRow'
import { CashPanelView } from '@/features/pos-shell/payment/CashPanelView'
import { DigitalInitiateView, DigitalPendingView } from '@/features/pos-shell/payment/DigitalPanelViews'
import { FullCoverageView } from '@/features/pos-shell/payment/FullCoverageView'
import { ChannelListPanelView } from '@/features/pos-shell/payment/ChannelListPanelView'
import { CheckoutErrorText } from '@/features/pos-shell/payment/CheckoutErrorText'
import { usePaymentAttempt } from '@/features/pos-shell/payment/usePaymentAttempt'
import { useSalesChannels, type SalesChannel } from '@/features/channels/channelsApi'
import { Spinner } from '@/components/ui/Spinner'
import { useQrisEffective, useStaticQrImageUrl, type QrisMode } from '@/features/payments/api'
import { effectiveQrisMode } from '@/features/payments/effectiveMode'
import { OfflineHint } from './offline/OfflineHint'
import { enqueueSale } from './offline/queue'
import type { ProvisionalTotals, SaleQueueRow } from './offline/db'
import type { DisplayPublisher } from './display/displayPublisher'
import type {
  OrderLineInput,
  OrderResponse,
  PaymentResponse,
  PriceBreakdownResponse,
} from './api'
import { useCheckout, useCapturePayment, usePayParked } from './api'

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
   * Phase 5 (ADR 0028): true when the terminal is currently offline. Forces CASH-only (tender
   * picker, digital panel, and gift-card redemption hidden behind an OfflineHint) and routes Pay
   * through the offline queue instead of POSTing — `breakdown` is then expected to already be
   * the caller's provisional breakdown (Pos.tsx swaps it in via `toDisplayBreakdown`).
   */
  offline?: boolean
  /** Called once the sale is durably enqueued (never after a real POST) instead of onSuccess — the
   * caller renders a provisional receipt. Only meaningful when `offline` is true. */
  onOfflineSuccess?: (row: SaleQueueRow, tenderedMinor: number, changeMinor: number) => void
  /** Phase 6 (ADR 0029): the customer-display publisher (a no-op until a display has been opened —
   * see displayPublisher.ts). */
  displayPublisher?: DisplayPublisher
}

/** The wire payload shared by the cash / digital / full-coverage attempts below. */
interface AttemptArgs {
  session: CompanySession
  lines: OrderLineInput[]
  discountMinor: number
  couponCode?: string | null
  loyaltyMemberId: string | null
  loyaltyRedeemPoints: number
  giftCardId: string | null
  giftCardRedeemMinor: number
  onSuccess: (order: OrderResponse, payment: PaymentResponse) => void
  onClose: () => void
  parkedOrderId?: string | null
  orderType?: string
  tableId?: string | null
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
  displayPublisher,
}: Props) {
  const { t } = useTranslation()
  const [tender, setTender] = useState<PosTender>('CASH')

  // ADR 0036 Phase B3: the ONLINE tender's channel roster — fetched only while this payment
  // surface is mounted (component-scoped, like usePaymentAttempt's key), and never while offline
  // (a platform order needs a live round-trip regardless).
  const channelsQuery = useSalesChannels(session, { enabled: !offline })

  // ADR 0045: the QRIS mode this outlet actually resolves to — fetched only while mounted and
  // never while offline (a digital mode is unreachable offline anyway, mirroring the channel
  // roster above). `currency` is the sale's own currency (rule 8) — GATEWAY degrades to MANUAL
  // for anything but IDR regardless of what the company configured.
  const qrisEffectiveQuery = useQrisEffective(session, session.businessId, { enabled: !offline })
  const qrisMode = effectiveQrisMode(qrisEffectiveQuery.data ?? undefined, qrisEffectiveQuery.isError, offline, currency)

  // Phase 4 (ADR 0027): gift-card redemption is entered HERE (unlike the loyalty member, which is
  // attached upstream). `residualDueMinor` is the identity the server's PriceBreakdownResponse
  // factory uses — no second quote round-trip needed (see GiftCardField's class doc).
  const [giftCard, setGiftCard] = useState<GiftCardResponse | null>(null)
  const [giftCardRedeemMinor, setGiftCardRedeemMinor] = useState(0)
  // Offline (Phase 5, ADR 0028): a gift card is a checkout-time server lookup — unreachable
  // offline, so it is never applied and the residual is simply the provisional grand total.
  const residualDueMinor = offline ? grandTotalMinor : Math.max(0, grandTotalMinor - giftCardRedeemMinor)
  const fullyCoveredByGiftCard = !offline && giftCard != null && residualDueMinor === 0

  // ADR 0036: ONLINE "carries no gift-card/loyalty legs" — the server rejects the combination, so
  // the picker disables ONLINE outright whenever either is active rather than ever letting the
  // cashier build the invalid payload. Offline is included too (TenderPickerRow only ever renders
  // reachable while !offline, but the guard is correct/self-documenting either way — ADR 0028).
  const onlineDisabledReason = offline
    ? t('offline.disabled.onlineTender')
    : giftCardRedeemMinor > 0 || (loyaltyRedeemPoints ?? 0) > 0
      ? t('pos.payment.onlineBlockedByRedemption')
      : null

  // Phase 6 (ADR 0029): opening this modal (and any change to what's due) is a PAYMENT_STARTED
  // transition for the customer display. No-ops when no display has ever been opened.
  useEffect(() => {
    displayPublisher?.publishPaymentStarted({ amountMinor: residualDueMinor, currency })
  }, [displayPublisher, residualDueMinor, currency])

  // The queue's stored breakdown mirrors the provisional one the caller already rendered.
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

  const attempt: AttemptArgs = {
    session,
    lines,
    discountMinor,
    couponCode,
    loyaltyMemberId: loyaltyMember?.id ?? null,
    loyaltyRedeemPoints: loyaltyRedeemPoints ?? 0,
    giftCardId: giftCard?.id ?? null,
    giftCardRedeemMinor,
    onSuccess,
    onClose,
    parkedOrderId,
    orderType,
    tableId,
  }

  return (
    <PaymentSurfaceFrame onClose={onClose}>
      {/* Offline: `breakdown` is already the caller's provisional breakdown, so this renders
          unchanged with its "estimated" badge doing double duty. */}
      <PaymentBreakdown breakdown={breakdown} grandTotalMinor={grandTotalMinor} currency={currency} locale={locale} />

      {/* Gift-card redemption (Phase 4, ADR 0027) — a checkout-time server lookup, unreachable
          offline (Phase 5, ADR 0028) and hidden for ONLINE (ADR 0036: the server rejects ONLINE
          combined with a gift-card redemption — never let the cashier build that payload). */}
      {offline ? (
        <div className="border-b border-line px-5 py-3 space-y-1.5">
          <OfflineHint text={t('offline.disabled.digitalTender')} />
          <OfflineHint text={t('offline.disabled.giftCard')} />
        </div>
      ) : tender === 'ONLINE' ? (
        <div className="border-b border-line px-5 py-3">
          <p className="text-xs text-ink-3">{t('pos.payment.giftCardUnavailableOnline')}</p>
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
        // Offline: cash only, no tender picker — the cash attempt enqueues instead of POSTing.
        <RestaurantCashAttempt
          attempt={{ ...attempt, couponCode: null, loyaltyRedeemPoints: 0, giftCardId: null, giftCardRedeemMinor: 0 }}
          chargeMinor={residualDueMinor}
          currency={currency}
          locale={locale}
          offline
          offlineProvisional={offlineProvisional}
          onOfflineSuccess={onOfflineSuccess}
        />
      ) : fullyCoveredByGiftCard ? (
        <RestaurantFullCoverageAttempt attempt={attempt} giftCardRedeemMinor={giftCardRedeemMinor} currency={currency} locale={locale} />
      ) : (
        <>
          <TenderPickerRow
            value={tender}
            onChange={setTender}
            showOnline
            onlineDisabledReason={onlineDisabledReason}
          />
          {tender === 'CASH' ? (
            <RestaurantCashAttempt attempt={attempt} chargeMinor={residualDueMinor} currency={currency} locale={locale} />
          ) : tender === 'ONLINE' ? (
            <RestaurantOnlineAttempt
              attempt={attempt}
              chargeMinor={residualDueMinor}
              currency={currency}
              locale={locale}
              channels={channelsQuery.data ?? []}
              channelsError={channelsQuery.isError}
            />
          ) : (
            <RestaurantDigitalAttempt
              attempt={attempt}
              chargeMinor={residualDueMinor}
              currency={currency}
              locale={locale}
              tenderType={tender}
              qrisMode={qrisMode}
            />
          )}
        </>
      )}
    </PaymentSurfaceFrame>
  )
}

// ---------------------------------------------------------------------------
// Cash attempt — one-shot checkout (or offline enqueue) with the payment block attached
// ---------------------------------------------------------------------------

function RestaurantCashAttempt({
  attempt,
  chargeMinor,
  currency,
  locale,
  offline = false,
  offlineProvisional,
  onOfflineSuccess,
}: {
  attempt: AttemptArgs
  chargeMinor: number
  currency: string
  locale: string
  offline?: boolean
  offlineProvisional?: ProvisionalTotals | null
  onOfflineSuccess?: (row: SaleQueueRow, tenderedMinor: number, changeMinor: number) => void
}) {
  const { session, lines, onSuccess, onClose, parkedOrderId, orderType, tableId } = attempt
  const checkout = useCheckout(session)
  const payParked = usePayParked(session)
  const [offlineError, setOfflineError] = useState<string | null>(null)
  const [offlineBusy, setOfflineBusy] = useState(false)

  // One idempotency key per payment ATTEMPT (panel mount), reused across retries — a retry after
  // an ambiguous failure replays the same key and resolves to the same order (never a second
  // charge or coupon redemption). Promotions review W2; the BillDetail pattern.
  const idempotencyKey = usePaymentAttempt()

  const isBusy = checkout.isPending || payParked.isPending || offlineBusy

  /**
   * Offline replacement for checkout.mutate: enqueues the sale (cash-only, no coupon/points/gift
   * card — the server 422s an offline replay carrying any of those) and PERSISTS it before
   * calling back, so a crash/reload between "Pay" and the confirmation can never lose the sale
   * nor show a false success. `loyaltyMemberId` alone IS forwarded — earn attribution is allowed
   * offline.
   */
  async function payOffline(tenderedMinor: number, changeMinor: number) {
    if (!offlineProvisional) return
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
          discountMinor: attempt.discountMinor && attempt.discountMinor > 0 ? attempt.discountMinor : null,
          orderType: orderType ?? null,
          tableId: tableId ?? null,
          loyaltyMemberId: attempt.loyaltyMemberId || null,
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

  function pay(tenderedMinor: number, changeMinor: number) {
    if (offline) {
      void payOffline(tenderedMinor, changeMinor)
      return
    }
    if (parkedOrderId) {
      // Resume path: finalise a PARKED order
      payParked.mutate(
        {
          orderId: parkedOrderId,
          payment: { tenderType: 'CASH', tenderedMinor },
          couponCode: attempt.couponCode,
          loyaltyMemberId: attempt.loyaltyMemberId,
          loyaltyRedeemPoints: attempt.loyaltyRedeemPoints,
          giftCardId: attempt.giftCardId,
          giftCardRedeemMinor: attempt.giftCardRedeemMinor,
        },
        { onSuccess: (res) => (res?.payment ? onSuccess(res, res.payment) : onClose()) },
      )
    } else {
      // Normal checkout path
      checkout.mutate(
        {
          idempotencyKey,
          lines,
          payment: { tenderType: 'CASH', tenderedMinor },
          discountMinor: attempt.discountMinor,
          orderType,
          tableId,
          couponCode: attempt.couponCode,
          loyaltyMemberId: attempt.loyaltyMemberId,
          loyaltyRedeemPoints: attempt.loyaltyRedeemPoints,
          giftCardId: attempt.giftCardId,
          giftCardRedeemMinor: attempt.giftCardRedeemMinor,
        },
        { onSuccess: (res) => (res?.payment ? onSuccess(res, res.payment) : onClose()) },
      )
    }
  }

  return (
    <CashPanelView
      chargeMinor={chargeMinor}
      currency={currency}
      locale={locale}
      initialTenderedMinor={chargeMinor}
      busy={isBusy}
      payDisabled={offline && offlineProvisional == null}
      errorSlot={
        <>
          {offlineError ? (
            <p className="mb-3 text-xs text-loss" role="alert">
              {offlineError}
            </p>
          ) : null}
          {!offline && (checkout.isError || payParked.isError) ? (
            <CheckoutErrorText error={checkout.error ?? payParked.error} />
          ) : null}
        </>
      }
      onPay={pay}
    />
  )
}

// ---------------------------------------------------------------------------
// Digital attempt (QRIS / Card) — two-step: checkout → PENDING → capture (ADR 0006)
// ---------------------------------------------------------------------------

function RestaurantDigitalAttempt({
  attempt,
  chargeMinor,
  currency,
  locale,
  tenderType,
  qrisMode,
}: {
  attempt: AttemptArgs
  chargeMinor: number
  currency: string
  locale: string
  tenderType: 'QRIS' | 'CARD'
  /** ADR 0045: irrelevant for CARD — only a QRIS tender in STATIC mode shows the merchant's own
   *  QR image instead of the demo "pending provider" copy. */
  qrisMode: QrisMode
}) {
  const { t } = useTranslation()
  const { session, lines, onSuccess, onClose, parkedOrderId, orderType, tableId } = attempt
  const checkout = useCheckout(session)
  const payParked = usePayParked(session)
  const capture = useCapturePayment(session)

  // ADR 0045: STATIC QRIS shows the merchant's own uploaded QR instead of the demo "pending
  // provider" badge/copy — CARD, or any other resolved mode, is untouched. The image is fetched
  // only once a PENDING payment exists (the panel it actually renders in, DigitalPendingView) —
  // fetching it earlier would just be wasted work while the cashier is still on the tender picker.
  const showStaticQr = tenderType === 'QRIS' && qrisMode === 'STATIC'

  // One idempotency key per payment ATTEMPT (panel mount), reused across retries (review W2).
  const idempotencyKey = usePaymentAttempt()

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
          couponCode: attempt.couponCode,
          loyaltyMemberId: attempt.loyaltyMemberId,
          loyaltyRedeemPoints: attempt.loyaltyRedeemPoints,
          giftCardId: attempt.giftCardId,
          giftCardRedeemMinor: attempt.giftCardRedeemMinor,
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
          discountMinor: attempt.discountMinor,
          orderType,
          tableId,
          couponCode: attempt.couponCode,
          loyaltyMemberId: attempt.loyaltyMemberId,
          loyaltyRedeemPoints: attempt.loyaltyRedeemPoints,
          giftCardId: attempt.giftCardId,
          giftCardRedeemMinor: attempt.giftCardRedeemMinor,
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

  // ADR 0045: fetched once the PENDING order exists — the same businessId checkout used.
  const staticQr = useStaticQrImageUrl(session, session.businessId, showStaticQr && pendingPayment != null)
  const staticQrSlot = showStaticQr ? (
    staticQr.status === 'ready' && staticQr.url ? (
      <img
        src={staticQr.url}
        alt={t('pos.payment.qris.scanToPay')}
        className="mx-auto block max-h-[260px] max-w-[260px] rounded-xl border border-line object-contain"
      />
    ) : staticQr.status === 'loading' ? (
      <div className="flex justify-center py-4">
        <Spinner className="text-brand-600" />
      </div>
    ) : (
      <p className="text-center text-xs text-loss">{t('pos.payment.qris.imageError')}</p>
    )
  ) : undefined

  if (!pendingPayment) {
    return (
      <DigitalInitiateView
        chargeMinor={chargeMinor}
        currency={currency}
        locale={locale}
        busy={checkout.isPending || payParked.isPending}
        errorSlot={
          checkout.isError || payParked.isError ? (
            <CheckoutErrorText error={checkout.error ?? payParked.error} />
          ) : null
        }
        onInitiate={initiatePayment}
      />
    )
  }

  return (
    <DigitalPendingView
      pendingAmountMinor={pendingPayment.amountMinor}
      currency={currency}
      locale={locale}
      busy={capture.isPending}
      captureError={capture.isError}
      onConfirm={confirmPayment}
      onCancel={onClose}
      qrSlot={staticQrSlot}
      hintText={showStaticQr ? t('pos.payment.qris.staticHint') : undefined}
      badgeText={showStaticQr ? null : undefined}
    />
  )
}

// ---------------------------------------------------------------------------
// Online attempt — a platform-channel order (ADR 0036 Phase B3): one-shot checkout, synchronous
// capture, EXACT amount (no keypad/change), no gift-card/loyalty legs.
// ---------------------------------------------------------------------------

function RestaurantOnlineAttempt({
  attempt,
  chargeMinor,
  currency,
  locale,
  channels,
  channelsError,
}: {
  attempt: AttemptArgs
  chargeMinor: number
  currency: string
  locale: string
  channels: SalesChannel[]
  channelsError: boolean
}) {
  const { t } = useTranslation()
  const { session, lines, onSuccess, onClose, parkedOrderId, orderType, tableId } = attempt
  const checkout = useCheckout(session)
  const payParked = usePayParked(session)

  // One idempotency key per payment ATTEMPT (panel mount), reused across retries (review W2).
  const idempotencyKey = usePaymentAttempt()

  const isBusy = checkout.isPending || payParked.isPending

  function pay(channelCode: string) {
    // Defense-in-depth (the tender picker already blocks ONLINE while either is active — see
    // onlineDisabledReason above): the ONLINE payload NEVER carries a gift-card or loyalty
    // redemption, mirroring the offline cash attempt's explicit-zero idiom.
    if (parkedOrderId) {
      payParked.mutate(
        {
          orderId: parkedOrderId,
          payment: { tenderType: 'ONLINE', channelCode },
          couponCode: attempt.couponCode,
          loyaltyMemberId: attempt.loyaltyMemberId,
          loyaltyRedeemPoints: 0,
          giftCardId: null,
          giftCardRedeemMinor: 0,
        },
        { onSuccess: (res) => (res?.payment ? onSuccess(res, res.payment) : onClose()) },
      )
    } else {
      checkout.mutate(
        {
          idempotencyKey,
          lines,
          payment: { tenderType: 'ONLINE', channelCode },
          discountMinor: attempt.discountMinor,
          orderType,
          tableId,
          couponCode: attempt.couponCode,
          loyaltyMemberId: attempt.loyaltyMemberId,
          loyaltyRedeemPoints: 0,
          giftCardId: null,
          giftCardRedeemMinor: 0,
        },
        { onSuccess: (res) => (res?.payment ? onSuccess(res, res.payment) : onClose()) },
      )
    }
  }

  return (
    <ChannelListPanelView
      channels={channels}
      chargeMinor={chargeMinor}
      currency={currency}
      locale={locale}
      busy={isBusy}
      errorSlot={
        <>
          {channelsError ? (
            <p className="mb-3 text-xs text-loss" role="alert">
              {t('pos.payment.channelsLoadError')}
            </p>
          ) : null}
          {checkout.isError || payParked.isError ? (
            <CheckoutErrorText error={checkout.error ?? payParked.error} />
          ) : null}
        </>
      }
      onPay={pay}
    />
  )
}

// ---------------------------------------------------------------------------
// Full-coverage attempt — the gift card covers the ENTIRE residual (Phase 4, ADR 0027)
// ---------------------------------------------------------------------------

/**
 * Wire-shape decision: the backend accepts EITHER omitting `payment` entirely OR sending a
 * payment object with the residual forced to zero (every vertical's writer special-cases a
 * fully-gift-card-covered sale into an immediate zero-amount CAPTURED record REGARDLESS of the
 * chosen tenderType — restaurant's `PaymentWriter#captureZeroResidualInCurrentTx`). This console
 * ALWAYS sends the payment object (`CASH`, `tenderedMinor: 0`) — the single wire shape that works
 * identically whether or not the vertical requires `payment` (carwash/barbershop's is @NotNull;
 * restaurant's is optional).
 */
function RestaurantFullCoverageAttempt({
  attempt,
  giftCardRedeemMinor,
  currency,
  locale,
}: {
  attempt: AttemptArgs
  giftCardRedeemMinor: number
  currency: string
  locale: string
}) {
  const { session, lines, onSuccess, parkedOrderId, orderType, tableId } = attempt
  const checkout = useCheckout(session)
  const payParked = usePayParked(session)
  const idempotencyKey = usePaymentAttempt()
  const isBusy = checkout.isPending || payParked.isPending

  function complete() {
    if (parkedOrderId) {
      payParked.mutate(
        {
          orderId: parkedOrderId,
          payment: { tenderType: 'CASH', tenderedMinor: 0 },
          couponCode: attempt.couponCode,
          loyaltyMemberId: attempt.loyaltyMemberId,
          loyaltyRedeemPoints: attempt.loyaltyRedeemPoints,
          giftCardId: attempt.giftCardId,
          giftCardRedeemMinor: attempt.giftCardRedeemMinor,
        },
        { onSuccess: (res) => res?.payment && onSuccess(res, res.payment) },
      )
    } else {
      checkout.mutate(
        {
          idempotencyKey,
          lines,
          payment: { tenderType: 'CASH', tenderedMinor: 0 },
          discountMinor: attempt.discountMinor,
          orderType,
          tableId,
          couponCode: attempt.couponCode,
          loyaltyMemberId: attempt.loyaltyMemberId,
          loyaltyRedeemPoints: attempt.loyaltyRedeemPoints,
          giftCardId: attempt.giftCardId,
          giftCardRedeemMinor: attempt.giftCardRedeemMinor,
        },
        { onSuccess: (res) => res?.payment && onSuccess(res, res.payment) },
      )
    }
  }

  return (
    <FullCoverageView
      giftCardRedeemMinor={giftCardRedeemMinor}
      currency={currency}
      locale={locale}
      busy={isBusy}
      errorSlot={
        checkout.isError || payParked.isError ? (
          <CheckoutErrorText error={checkout.error ?? payParked.error} />
        ) : null
      }
      onComplete={complete}
    />
  )
}
