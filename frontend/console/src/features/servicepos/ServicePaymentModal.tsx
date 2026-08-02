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
import { useState } from 'react'
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
import { CheckoutErrorText } from '@/features/pos-shell/payment/CheckoutErrorText'
import { usePaymentAttempt } from '@/features/pos-shell/payment/usePaymentAttempt'
import { OfflineHint } from '@/features/pos/offline/OfflineHint'
import { enqueueSale } from '@/features/pos/offline/queue'
import type { ProvisionalTotals, SaleQueueRow } from '@/features/pos/offline/db'
import type { VerticalPosConfig } from './config'
import {
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
  const [tender, setTender] = useState<PosTender>('CASH')

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
    <PaymentSurfaceFrame onClose={onClose}>
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
          <TenderPickerRow value={tender} onChange={setTender} />
          {tender === 'CASH' ? (
            <ServiceCashAttempt attempt={attempt} chargeMinor={residualDueMinor} currency={currency} locale={locale} />
          ) : (
            <ServiceDigitalAttempt attempt={attempt} chargeMinor={residualDueMinor} currency={currency} locale={locale} tenderType={tender} />
          )}
        </>
      )}
    </PaymentSurfaceFrame>
  )
}

/** The checkout mutation body every attempt shares (the ticket wire shape). */
function ticketBody(a: TicketAttemptArgs, idempotencyKey: string, payment: { tenderType: PosTender; tenderedMinor?: number }) {
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
}: {
  attempt: TicketAttemptArgs
  chargeMinor: number
  currency: string
  locale: string
  tenderType: 'QRIS' | 'CARD'
}) {
  const { config, session, onSuccess, onClose } = attempt
  const checkout = useTicketCheckout(config, session)
  const capture = useTicketCapture(config, session)

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
        if (captured) onSuccess(captured)
      },
    })
  }

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

  return (
    <DigitalPendingView
      pendingAmountMinor={pendingTicket.payment?.amountMinor ?? chargeMinor}
      currency={currency}
      locale={locale}
      busy={capture.isPending}
      captureError={capture.isError}
      onConfirm={confirmPayment}
      onCancel={onClose}
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
