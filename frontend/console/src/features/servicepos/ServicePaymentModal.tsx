/**
 * ServicePaymentModal — the service-POS payment step (carwash/barbershop tickets).
 *
 * Redesign P3: now the service ADAPTER over the shared payment surface in
 * features/pos-shell/payment (the markup previously duplicated from the restaurant
 * PaymentModal lives there once). Everything behavioral stays HERE: the ticket
 * checkout/capture mutations (POST {apiBase}/tickets/checkout, /tickets/{id}/capture — ADR
 * 0006's two-step digital contract), the per-attempt idempotency key (usePaymentAttempt, review
 * W1), the offline enqueue with the config-driven endpoint + location field name (ADR 0028),
 * and the gift-card residual math (ADR 0027; `payment` is REQUIRED on this wire, so the
 * full-coverage path always sends the zero-amount CASH object).
 *
 * Money rule (rule 8): integer minor units; rendered via formatMoney().
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { Gift } from 'lucide-react'
import { GiftCardField } from '@/components/GiftCardField'
import type { CompanySession } from '@/lib/session'
import type { GiftCardResponse, MemberResponse } from '@/features/loyalty/api'
import { PaymentSurfaceFrame } from '@/features/pos-shell/payment/PaymentSurfaceFrame'
import { PaymentBreakdown } from '@/features/pos-shell/payment/PaymentBreakdown'
import { TenderPickerRow } from '@/features/pos-shell/payment/TenderPickerRow'
import { CashPanelView } from '@/features/pos-shell/payment/CashPanelView'
import { DigitalInitiateView, DigitalPendingView } from '@/features/pos-shell/payment/DigitalPanelViews'
import { GatewayQrisPendingView } from '@/features/pos-shell/payment/QrisPanelViews'
import { pollIntervalFor } from '@/features/pos-shell/payment/pollIntervalFor'
import { FullCoverageView } from '@/features/pos-shell/payment/FullCoverageView'
import { CheckoutErrorText } from '@/features/pos-shell/payment/CheckoutErrorText'
import { usePaymentAttempt } from '@/features/pos-shell/payment/usePaymentAttempt'
import { Spinner } from '@/components/ui/Spinner'
import { useQrisEffective, useStaticQrImageUrl, type QrisMode } from '@/features/payments/api'
import { effectiveQrisMode } from '@/features/payments/effectiveMode'
import { useGatewayQris } from '@/features/payments/useGatewayQris'
import { OfflineHint } from '@/features/pos/offline/OfflineHint'
import { enqueueSale } from '@/features/pos/offline/queue'
import type { ProvisionalTotals, SaleQueueRow } from '@/features/pos/offline/db'
import type { VerticalPosConfig } from './config'
import {
  useTicket,
  useTicketCapture,
  useTicketCheckout,
  type PriceBreakdownResponse,
  type TicketLineInput,
  type TicketResponse,
} from './api'

interface Props {
  config: VerticalPosConfig
  session: CompanySession
  lines: TicketLineInput[]
  /** Server-authoritative grand total from the quote breakdown; drives the charge amount BEFORE
   * any gift-card tender. */
  grandTotalMinor: number
  discountMinor: number
  /** Phase 3 (ADR 0026): the committed coupon code (or null); forwarded to checkout. */
  couponCode?: string | null
  /** Phase 4 (ADR 0027): the loyalty member attached upstream on the main POS screen (read-only
   * here — attach/detach lives in MemberField, in ServicePos's SummaryPanel). */
  loyaltyMember?: MemberResponse | null
  loyaltyRedeemPoints?: number
  /** Live price breakdown from useTicketQuote — rendered in the modal header. */
  breakdown: PriceBreakdownResponse | null
  currency: string
  locale: string
  bay: string
  vehiclePlate: string
  staffProfileId: string | null
  onSuccess: (ticket: TicketResponse) => void
  onClose: () => void
  /** Phase 5 (ADR 0028): true when the terminal is offline — see PaymentModal's twin doc. */
  offline?: boolean
  onOfflineSuccess?: (row: SaleQueueRow, tenderedMinor: number, changeMinor: number) => void
}

/** The ticket-checkout wire payload shared by the cash/digital/full-coverage attempts below. */
interface TicketAttemptArgs {
  config: VerticalPosConfig
  session: CompanySession
  lines: TicketLineInput[]
  discountMinor: number
  couponCode?: string | null
  loyaltyMemberId: string | null
  loyaltyRedeemPoints: number
  giftCardId: string | null
  giftCardRedeemMinor: number
  bay: string
  vehiclePlate: string
  staffProfileId: string | null
  onSuccess: (ticket: TicketResponse) => void
  onClose: () => void
}

/**
 * Carwash/barbershop tickets have no channel-checkout contract yet (ADR 0036 Phase B3 is
 * restaurant orders/bills only) — this vertical's own tender stays the pre-ONLINE trio.
 * `TenderPickerRow` defaults `showOnline` to false, so the ONLINE option is never rendered here;
 * this local, narrower type keeps every wire payload below honest about that at compile time too.
 */
type ServiceTender = 'CASH' | 'QRIS' | 'CARD'

export function ServicePaymentModal({
  config,
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
  bay,
  vehiclePlate,
  staffProfileId,
  onSuccess,
  onClose,
  offline = false,
  onOfflineSuccess,
}: Props) {
  const { t } = useTranslation()
  const [tender, setTender] = useState<ServiceTender>('CASH')

  // ADR 0045: the QRIS mode this outlet actually resolves to — see PaymentModal's twin doc
  // (never fetched while offline; `currency` is the sale's own currency, rule 8).
  const qrisEffectiveQuery = useQrisEffective(session, session.businessId, { enabled: !offline })
  const qrisMode = effectiveQrisMode(qrisEffectiveQuery.data ?? undefined, qrisEffectiveQuery.isError, offline, currency)

  // ADR 0045: while a GATEWAY charge is live, `ServiceDigitalAttempt` registers its cancel function
  // here so the FRAME's own X close button cancels the charge before closing — see PaymentModal's
  // `handleFrameClose` twin doc (identical contract).
  const gatewayCancelRef = useRef<(() => Promise<boolean>) | null>(null)
  const registerGatewayCancel = useCallback((fn: (() => Promise<boolean>) | null) => {
    gatewayCancelRef.current = fn
  }, [])
  function handleFrameClose() {
    const cancelFn = gatewayCancelRef.current
    if (!cancelFn) {
      onClose()
      return
    }
    gatewayCancelRef.current = null
    void cancelFn().then((capturedInFlight) => {
      if (!capturedInFlight) onClose()
    })
  }

  // Phase 4 (ADR 0027): gift-card redemption entered HERE — see PaymentModal's class doc for the
  // residual/full-coverage design (identical here).
  const [giftCard, setGiftCard] = useState<GiftCardResponse | null>(null)
  const [giftCardRedeemMinor, setGiftCardRedeemMinor] = useState(0)
  // Offline (Phase 5, ADR 0028) — see PaymentModal's twin doc.
  const residualDueMinor = offline ? grandTotalMinor : Math.max(0, grandTotalMinor - giftCardRedeemMinor)
  const fullyCoveredByGiftCard = !offline && giftCard != null && residualDueMinor === 0
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

  const attempt: TicketAttemptArgs = {
    config,
    session,
    lines,
    discountMinor,
    couponCode,
    loyaltyMemberId: loyaltyMember?.id ?? null,
    loyaltyRedeemPoints: loyaltyRedeemPoints ?? 0,
    giftCardId: giftCard?.id ?? null,
    giftCardRedeemMinor,
    bay,
    vehiclePlate,
    staffProfileId,
    onSuccess,
    onClose,
  }

  return (
    <PaymentSurfaceFrame onClose={handleFrameClose}>
      <PaymentBreakdown breakdown={breakdown} grandTotalMinor={grandTotalMinor} currency={currency} locale={locale} />

      {/* Gift-card redemption (Phase 4, ADR 0027) — unreachable offline (Phase 5, ADR 0028). */}
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
        <ServiceCashAttempt
          attempt={{ ...attempt, couponCode: null, loyaltyRedeemPoints: 0, giftCardId: null, giftCardRedeemMinor: 0 }}
          chargeMinor={residualDueMinor}
          currency={currency}
          locale={locale}
          offline
          offlineProvisional={offlineProvisional}
          onOfflineSuccess={onOfflineSuccess}
        />
      ) : fullyCoveredByGiftCard ? (
        <ServiceFullCoverageAttempt attempt={attempt} giftCardRedeemMinor={giftCardRedeemMinor} currency={currency} locale={locale} />
      ) : (
        <>
          {/* showOnline defaults false — see the ServiceTender doc above. The narrowing guard
              (excluding 'ONLINE') is unreachable in practice but keeps this type-safe without a
              cast, since TenderPickerRow's onChange is typed against the shared PosTender union. */}
          <TenderPickerRow value={tender} onChange={(t) => t !== 'ONLINE' && setTender(t)} />
          {tender === 'CASH' ? (
            <ServiceCashAttempt attempt={attempt} chargeMinor={residualDueMinor} currency={currency} locale={locale} />
          ) : (
            <ServiceDigitalAttempt
              attempt={attempt}
              chargeMinor={residualDueMinor}
              currency={currency}
              locale={locale}
              tenderType={tender}
              qrisMode={qrisMode}
              registerGatewayCancel={registerGatewayCancel}
            />
          )}
        </>
      )}
    </PaymentSurfaceFrame>
  )
}

/** The checkout mutation body every attempt shares (the ticket wire shape). */
function ticketBody(a: TicketAttemptArgs, idempotencyKey: string, payment: { tenderType: ServiceTender; tenderedMinor?: number }) {
  return {
    idempotencyKey,
    bay: a.bay,
    vehiclePlate: a.vehiclePlate || null,
    staffProfileId: a.staffProfileId,
    discountMinor: a.discountMinor,
    lines: a.lines,
    payment,
    couponCode: a.couponCode,
    loyaltyMemberId: a.loyaltyMemberId,
    loyaltyRedeemPoints: a.loyaltyRedeemPoints,
    giftCardId: a.giftCardId,
    giftCardRedeemMinor: a.giftCardRedeemMinor,
  }
}

// ---------------------------------------------------------------------------
// Cash attempt — one-shot ticket checkout (or offline enqueue)
// ---------------------------------------------------------------------------

function ServiceCashAttempt({
  attempt,
  chargeMinor,
  currency,
  locale,
  offline = false,
  offlineProvisional,
  onOfflineSuccess,
}: {
  attempt: TicketAttemptArgs
  chargeMinor: number
  currency: string
  locale: string
  offline?: boolean
  offlineProvisional?: ProvisionalTotals | null
  onOfflineSuccess?: (row: SaleQueueRow, tenderedMinor: number, changeMinor: number) => void
}) {
  const { config, session, onSuccess } = attempt
  const checkout = useTicketCheckout(config, session)
  const [offlineError, setOfflineError] = useState<string | null>(null)
  const [offlineBusy, setOfflineBusy] = useState(false)

  // One idempotency key per payment ATTEMPT (panel mount), reused across retries (review W1).
  const idempotencyKey = usePaymentAttempt()

  /** Offline replacement for checkout.mutate — see PaymentModal's CashPanel twin doc.
   * `loyaltyMemberId` alone is forwarded (earn attribution allowed offline). */
  async function payOffline(tenderedMinor: number, changeMinor: number) {
    if (!offlineProvisional) return
    setOfflineBusy(true)
    setOfflineError(null)
    try {
      const row = await enqueueSale({
        vertical: config.vertical as 'carwash' | 'barbershop',
        endpoint: `${config.apiBase}/tickets/checkout`,
        companyId: session.companyId,
        actor: session.actor,
        body: {
          businessId: session.businessId,
          lines: attempt.lines,
          [config.location.fieldName]: attempt.bay.trim() ? attempt.bay : null,
          ...(config.vehicleField ? { vehiclePlate: attempt.vehiclePlate || null } : {}),
          staffProfileId: attempt.staffProfileId || null,
          discountMinor: attempt.discountMinor && attempt.discountMinor > 0 ? attempt.discountMinor : null,
          payment: { tenderType: 'CASH', tenderedMinor },
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
    checkout.mutate(ticketBody(attempt, idempotencyKey, { tenderType: 'CASH', tenderedMinor }), {
      onSuccess: (res) => {
        if (res) onSuccess(res)
      },
    })
  }

  return (
    <CashPanelView
      chargeMinor={chargeMinor}
      currency={currency}
      locale={locale}
      initialTenderedMinor={chargeMinor}
      busy={checkout.isPending || offlineBusy}
      payDisabled={offline && offlineProvisional == null}
      errorSlot={
        <>
          {offlineError ? (
            <p className="mb-3 text-xs text-loss" role="alert">
              {offlineError}
            </p>
          ) : null}
          {!offline && checkout.isError ? <CheckoutErrorText error={checkout.error} /> : null}
        </>
      }
      onPay={pay}
    />
  )
}

// ---------------------------------------------------------------------------
// Digital attempt (QRIS / Card) — two-step: checkout → PENDING → capture (ADR 0006)
// ---------------------------------------------------------------------------

function ServiceDigitalAttempt({
  attempt,
  chargeMinor,
  currency,
  locale,
  tenderType,
  qrisMode,
  registerGatewayCancel,
}: {
  attempt: TicketAttemptArgs
  chargeMinor: number
  currency: string
  locale: string
  tenderType: 'QRIS' | 'CARD'
  /** ADR 0045: irrelevant for CARD — see PaymentModal's twin doc. */
  qrisMode: QrisMode
  /** ADR 0045: lets this attempt register the live gateway-cancel function with the modal frame —
   *  see ServicePaymentModal's `handleFrameClose` doc (identical contract to PaymentModal's). */
  registerGatewayCancel?: (fn: (() => Promise<boolean>) | null) => void
}) {
  const { t } = useTranslation()
  const { config, session, onSuccess, onClose } = attempt
  const qc = useQueryClient()
  const checkout = useTicketCheckout(config, session)
  const capture = useTicketCapture(config, session)

  // ADR 0045: see PaymentModal's twin doc — fetched only once a PENDING ticket exists.
  const showStaticQr = tenderType === 'QRIS' && qrisMode === 'STATIC'
  // ADR 0045: see PaymentModal's twin doc for the full GATEWAY/fallback/captured-beats-cancel design
  // (identical contract here, over the ticket wire instead of the order wire).
  const showGatewayQris = tenderType === 'QRIS' && qrisMode === 'GATEWAY'
  const [gatewayFallback, setGatewayFallback] = useState(false)
  const capturedRef = useRef(false)

  // One idempotency key per payment ATTEMPT (panel mount), reused across retries (review W1).
  const idempotencyKey = usePaymentAttempt()

  // After initiation we hold the PENDING ticket to drive the "Mark as paid" step.
  const [pendingTicket, setPendingTicket] = useState<TicketResponse | null>(null)

  function initiatePayment() {
    checkout.mutate(ticketBody(attempt, idempotencyKey, { tenderType }), {
      onSuccess: (res) => {
        if (res?.payment) setPendingTicket(res)
      },
    })
  }

  function confirmPayment() {
    if (!pendingTicket) return
    capture.mutate(pendingTicket.ticketId, {
      onSuccess: (captured) => {
        if (captured && !capturedRef.current) {
          capturedRef.current = true
          onSuccess(captured)
        }
      },
    })
  }

  // ADR 0045: fetched once the PENDING ticket exists — the same businessId checkout used.
  const staticQr = useStaticQrImageUrl(session, session.businessId, showStaticQr && pendingTicket != null)
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

  // ADR 0045: the GATEWAY lifecycle — see PaymentModal's twin doc. `referenceId` carries the ticket
  // id (CreateChargeRequest.referenceId — null for restaurant, required here).
  const gatewayActive = showGatewayQris && !gatewayFallback
  const pendingPayment = pendingTicket?.payment ?? null
  const gateway = useGatewayQris(session, {
    vertical: config.vertical,
    paymentId: gatewayActive && pendingPayment ? pendingPayment.paymentId : null,
    referenceId: pendingTicket?.ticketId ?? null,
    businessId: session.businessId,
    amountMinor: pendingPayment?.amountMinor ?? chargeMinor,
    currency,
  })

  // ADR 0045: the vertical-read poll — polls the SAME ticket read "Mark as paid" would show,
  // watching `payment.status` for CAPTURED (see PaymentModal's twin `useReceipt` doc).
  const ticketQuery = useTicket(config, session, gatewayActive && pendingTicket ? pendingTicket.ticketId : null, {
    refetchInterval: (query) =>
      pollIntervalFor(query.state.data?.payment?.status ?? null, query.state.status === 'error'),
  })

  useEffect(() => {
    if (!gatewayActive || capturedRef.current) return
    const captured = ticketQuery.data
    if (captured?.payment?.status === 'CAPTURED') {
      capturedRef.current = true
      void qc.invalidateQueries({ queryKey: ['pnl'] })
      onSuccess(captured)
    }
  }, [gatewayActive, ticketQuery.data, onSuccess, qc])

  const handleGatewayCancel = useCallback(async (): Promise<boolean> => {
    const capturedInFlight = await gateway.cancel()
    if (capturedInFlight || capturedRef.current) return true
    setGatewayFallback(true)
    return false
  }, [gateway])

  useEffect(() => {
    if (!gatewayActive || !registerGatewayCancel) return undefined
    registerGatewayCancel(handleGatewayCancel)
    return () => registerGatewayCancel(null)
  }, [gatewayActive, registerGatewayCancel, handleGatewayCancel])

  if (!pendingTicket) {
    return (
      <DigitalInitiateView
        chargeMinor={chargeMinor}
        currency={currency}
        locale={locale}
        busy={checkout.isPending}
        errorSlot={checkout.isError ? <CheckoutErrorText error={checkout.error} /> : null}
        onInitiate={initiatePayment}
      />
    )
  }

  if (gatewayActive) {
    return (
      <GatewayQrisPendingView
        amountMinor={pendingPayment?.amountMinor ?? chargeMinor}
        currency={currency}
        locale={locale}
        qrString={gateway.charge?.qrString ?? null}
        expiresAtMs={gateway.charge?.expiresAt ? new Date(gateway.charge.expiresAt).getTime() : null}
        phase={gateway.phase}
        errorSlot={
          gateway.phase !== 'error' && ticketQuery.isError ? (
            <p className="mb-3 text-xs text-loss" role="alert">
              {t('pos.payment.qris.pollDegraded')}
            </p>
          ) : undefined
        }
        onCancel={() => void handleGatewayCancel()}
        onNewQr={gateway.retryOrNewQr}
        onCheckStatus={gateway.checkStatus}
        onManualConfirm={confirmPayment}
        manualConfirmBusy={capture.isPending}
      />
    )
  }

  return (
    <DigitalPendingView
      pendingAmountMinor={pendingTicket.payment?.amountMinor ?? chargeMinor}
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
// Full-coverage attempt — the gift card covers the ENTIRE residual (Phase 4, ADR 0027).
// ServicePaymentModal's payment field is REQUIRED on the wire (unlike restaurant's optional
// one), so sending the zero-amount CASH payment is the only valid shape.
// ---------------------------------------------------------------------------

function ServiceFullCoverageAttempt({
  attempt,
  giftCardRedeemMinor,
  currency,
  locale,
}: {
  attempt: TicketAttemptArgs
  giftCardRedeemMinor: number
  currency: string
  locale: string
}) {
  const { config, session, onSuccess } = attempt
  const checkout = useTicketCheckout(config, session)
  const idempotencyKey = usePaymentAttempt()

  function complete() {
    checkout.mutate(ticketBody(attempt, idempotencyKey, { tenderType: 'CASH', tenderedMinor: 0 }), {
      onSuccess: (res) => res && onSuccess(res),
    })
  }

  return (
    <FullCoverageView
      giftCardRedeemMinor={giftCardRedeemMinor}
      currency={currency}
      locale={locale}
      busy={checkout.isPending}
      errorSlot={checkout.isError ? <CheckoutErrorText error={checkout.error} /> : null}
      onComplete={complete}
    />
  )
}
