/**
 * BillPaymentModal — payment step for a guest bill (tab), incl. split checks.
 *
 * Redesign P3: now the bill ADAPTER over the shared payment surface in
 * features/pos-shell/payment. Wired to POST /api/v1/bills/{id}/pay instead of the order
 * checkout flow. Bill-mode deltas the adapter owns (all pre-existing behavior):
 *   - The idempotency key is minted by BillDetail per pay-initiation and passed as a PROP
 *     (freshIdempotencyKey — see usePaymentAttempt's doc for why bills are the exception).
 *   - Digital tenders are ONE-step (pay directly; no PENDING/capture leg) with a ghost cancel —
 *     EXCEPT a full-bill QRIS tender resolving to GATEWAY mode (ADR 0045 extension, below), which
 *     gets the same two-step dynamic-QR flow the ORDER modal's `RestaurantDigitalAttempt` already
 *     has (see PaymentModal.tsx). Split checks are unchanged — they always keep the one-step flow
 *     (`shouldUseBillGatewayFlow` in features/pos/lib/billGatewayQris.ts).
 *   - No gift-card field, no coupon/loyalty detail (ADR 0026/0027 scope), 'simple' breakdown —
 *     which also means ONLINE (ADR 0036 Phase B3) has nothing to hide/disable here: bills never
 *     carry a gift-card or loyalty redemption in the first place, so the tender is always offered.
 *   - Stacks over the bill sheet at z-[60].
 *
 * Money rule (rule 8): all amounts are integer minor units.
 * Strings rule (rule 9): all user-facing text is in i18n keys.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useIsMutating, useQueryClient } from '@tanstack/react-query'
import type { CompanySession } from '@/lib/session'
import { PaymentSurfaceFrame } from '@/features/pos-shell/payment/PaymentSurfaceFrame'
import { PaymentBreakdown } from '@/features/pos-shell/payment/PaymentBreakdown'
import { TenderPickerRow, type PosTender } from '@/features/pos-shell/payment/TenderPickerRow'
import { CashPanelView } from '@/features/pos-shell/payment/CashPanelView'
import { DigitalInitiateView } from '@/features/pos-shell/payment/DigitalPanelViews'
import { GatewayQrisPendingView } from '@/features/pos-shell/payment/QrisPanelViews'
import { pollIntervalFor } from '@/features/pos-shell/payment/pollIntervalFor'
import { ChannelListPanelView } from '@/features/pos-shell/payment/ChannelListPanelView'
import { CheckoutErrorText } from '@/features/pos-shell/payment/CheckoutErrorText'
import { useSalesChannels } from '@/features/channels/channelsApi'
import { Spinner } from '@/components/ui/Spinner'
import { useQrisEffective, useStaticQrImageUrl } from '@/features/payments/api'
import { effectiveQrisMode } from '@/features/payments/effectiveMode'
import { useGatewayQris } from '@/features/payments/useGatewayQris'
import { shouldUseBillGatewayFlow } from './lib/billGatewayQris'
import { isCaptureObserved, isCleanCancel } from './lib/billGatewayCapture'
import type { PaymentResponse } from './api'
import { useCapturePayment, useReceipt } from './api'
import { usePayBill, usePayBillPending, useAbandonPayment, type BillResponse } from './billsApi'

/** What was actually paid — reported to onSuccess so the check receipt shows the real tender. */
export interface BillPaidInfo {
  tenderType: PosTender
  /** Amount handed over in minor units (CASH only). */
  tenderedMinor?: number
  /** Change due in minor units (CASH only). */
  changeMinor?: number
}

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
  onSuccess: (paid: BillPaidInfo) => void
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
  const [tender, setTender] = useState<PosTender>('CASH')
  // Back must not dismiss the surface while a pay/capture mutation is posting — see PaymentModal.
  const mutationsInFlight = useIsMutating()
  const payBill = usePayBill(session)
  // ADR 0036 Phase B3: fetched only while this payment surface is mounted, same as PaymentModal.
  const channelsQuery = useSalesChannels(session)

  const currency = bill.currency
  const isSplitCheck = !!(lineIds && lineIds.length > 0)

  // ADR 0045: the QRIS mode this outlet actually resolves to — bills have no offline mode, so
  // always fetched while mounted (see PaymentModal's twin doc for the fetch/degrade rules). ADR
  const qrisEffectiveQuery = useQrisEffective(session, session.businessId)
  const qrisMode = effectiveQrisMode(qrisEffectiveQuery.data ?? undefined, qrisEffectiveQuery.isError, false, currency)
  // ADR 0045 extension (bills): a full-bill QRIS tender resolving to GATEWAY drives the SAME
  // two-step dynamic-QR flow the order modal has (BillGatewayDigitalAttempt, below) instead of the
  // one-step Pay every other tender/mode keeps. Split checks are explicitly out of scope this pass.
  const useGatewayFlow = shouldUseBillGatewayFlow(tender, qrisMode, isSplitCheck)
  // Bills are one-step (pay directly) — STATIC shows the merchant's QR above the Pay button so the
  // customer can scan BEFORE the cashier confirms, unlike the two-step surfaces' pending panel.
  const showStaticQr = tender === 'QRIS' && qrisMode === 'STATIC'
  // ADR 0045 amendment: configured GATEWAY degraded to MANUAL — honest badge, not the demo copy
  // (bills have no offline mode; gated on IDR so a non-IDR currency limitation isn't misattributed).
  // Unrelated to `useGatewayFlow`/`isSplitCheck`: a split check on a genuinely-connected GATEWAY
  // outlet isn't degraded, it's just out of scope for the two-step leg — the one-step panel there
  // keeps the pre-existing demo "pending provider" badge (unchanged, unsplit-check behavior).
  const degradedFromGateway =
    qrisEffectiveQuery.data?.mode === 'GATEWAY' && qrisMode !== 'GATEWAY' && currency === 'IDR'
  const staticQr = useStaticQrImageUrl(session, session.businessId, showStaticQr, 0)
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

  // For a split check use the caller-supplied subtotal; otherwise fall back to the full breakdown.
  const grandTotalMinor =
    checkTotalMinor ??
    bill.breakdown?.grandTotalMinor ??
    bill.lines.reduce((s, l) => s + l.lineTotalMinor, 0)
  // Only show the full breakdown when this is NOT a split check (partial checks have no server breakdown).
  const breakdown = lineIds && lineIds.length > 0 ? null : bill.breakdown

  // ADR 0045 extension (bills): while a GATEWAY charge is live, BillGatewayDigitalAttempt registers
  // its cancel function here so the FRAME's own X close button cancels/abandons the pending payment
  // before closing — mirrors PaymentModal's `handleFrameClose` doc verbatim.
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

  const errorSlot = payBill.isError ? <CheckoutErrorText error={payBill.error} /> : null
  const onlineErrorSlot = (
    <>
      {channelsQuery.isError ? (
        <p className="mb-3 text-xs text-loss" role="alert">
          {t('pos.payment.channelsLoadError')}
        </p>
      ) : null}
      {errorSlot}
    </>
  )

  function pay(payment: { tenderType: PosTender; tenderedMinor?: number; channelCode?: string }) {
    payBill.mutate(
      { billId: bill.id, payment, lineIds, idempotencyKey },
      {
        onSuccess: () =>
          onSuccess({
            tenderType: payment.tenderType,
            tenderedMinor: payment.tenderedMinor,
            changeMinor:
              payment.tenderType === 'CASH' && payment.tenderedMinor != null
                ? Math.max(0, payment.tenderedMinor - grandTotalMinor)
                : undefined,
          }),
      },
    )
  }

  return (
    <PaymentSurfaceFrame
      zIndexClass="z-[60]"
      subtitle={bill.guestLabel}
      onClose={handleFrameClose}
      backDismissEnabled={mutationsInFlight === 0}
    >
      <PaymentBreakdown
        breakdown={breakdown}
        grandTotalMinor={grandTotalMinor}
        currency={currency}
        locale={locale}
        variant="simple"
      />

      <TenderPickerRow value={tender} onChange={setTender} showOnline />

      {tender === 'CASH' ? (
        <CashPanelView
          chargeMinor={grandTotalMinor}
          currency={currency}
          locale={locale}
          initialTenderedMinor={grandTotalMinor}
          busy={payBill.isPending}
          errorSlot={errorSlot}
          onPay={(tenderedMinor) => pay({ tenderType: 'CASH', tenderedMinor })}
        />
      ) : tender === 'ONLINE' ? (
        <ChannelListPanelView
          channels={channelsQuery.data ?? []}
          chargeMinor={grandTotalMinor}
          currency={currency}
          locale={locale}
          busy={payBill.isPending}
          errorSlot={onlineErrorSlot}
          onPay={(channelCode) => pay({ tenderType: 'ONLINE', channelCode })}
        />
      ) : useGatewayFlow ? (
        // ADR 0045 extension (bills): full-bill QRIS resolving to GATEWAY — the two-step dynamic-QR
        // flow (create PENDING → auto-charge → poll/manual capture), mirroring the order modal.
        <BillGatewayDigitalAttempt
          session={session}
          billId={bill.id}
          idempotencyKey={idempotencyKey}
          chargeMinor={grandTotalMinor}
          currency={currency}
          locale={locale}
          onSuccess={onSuccess}
          onClose={onClose}
          registerGatewayCancel={registerGatewayCancel}
        />
      ) : (
        // Bills settle digital tenders in ONE step (no PENDING/capture leg) with a ghost cancel.
        <DigitalInitiateView
          chargeMinor={grandTotalMinor}
          currency={currency}
          locale={locale}
          busy={payBill.isPending}
          errorSlot={errorSlot}
          onInitiate={() => pay({ tenderType: tender })}
          onCancel={onClose}
          qrSlot={staticQrSlot}
          hintText={
            showStaticQr
              ? t('pos.payment.qris.staticHint')
              : degradedFromGateway
                ? t('pos.payment.qris.gatewayDegradedHint')
                : undefined
          }
          badgeText={
            showStaticQr
              ? null
              : degradedFromGateway
                ? t('pos.payment.qris.gatewayDegradedBadge')
                : undefined
          }
        />
      )}
    </PaymentSurfaceFrame>
  )
}

// ---------------------------------------------------------------------------
// Gateway digital attempt (QRIS, full-bill only) — two-step: pay-pending → auto-charge → capture
// (ADR 0045 extension to bills). Mirrors PaymentModal.tsx's `RestaurantDigitalAttempt` closely, but
// simpler: bills carry no coupon/loyalty/gift-card legs and this leg never covers a split check
// (see `shouldUseBillGatewayFlow`), so there is no "resume a parked order" branch and no gift-card
// math — just billId + the fixed full-bill charge.
// ---------------------------------------------------------------------------

function BillGatewayDigitalAttempt({
  session,
  billId,
  idempotencyKey,
  chargeMinor,
  currency,
  locale,
  onSuccess,
  onClose,
  registerGatewayCancel,
}: {
  session: CompanySession
  billId: string
  /** The SAME per-pay-initiation key BillDetail mints (freshIdempotencyKey) — reused across retries
   *  of this attempt, mirroring the existing one-step bill flow's exception to `usePaymentAttempt`. */
  idempotencyKey?: string
  chargeMinor: number
  currency: string
  locale: string
  onSuccess: (paid: BillPaidInfo) => void
  onClose: () => void
  /** ADR 0045: lets this attempt register its live gateway-cancel function with the modal frame —
   *  see BillPaymentModal's `handleFrameClose` doc. */
  registerGatewayCancel: (fn: (() => Promise<boolean>) | null) => void
}) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const payPending = usePayBillPending(session)
  const capture = useCapturePayment(session)
  const abandon = useAbandonPayment(session)

  // Captured beats cancel (ADR 0045): flips true the FIRST time either the vertical-read poll or a
  // manual "Mark as paid" observes CAPTURED — guards onSuccess from firing twice.
  const capturedRef = useRef(false)

  // After initiation we hold the PENDING payment to drive the gateway QR + "Mark as paid" step. A
  // clean cancel (in-panel Cancel, or the frame's X while pending) ABANDONS this payment server-side
  // (releasing the bill's line reservation) and resets it to null — unlike the order flow, there is
  // no intermediate "manual pending" fallback view: the reservation is gone, so the only sane next
  // step is re-initiating from the top (the initiate view, below).
  const [pendingPayment, setPendingPayment] = useState<PaymentResponse | null>(null)

  function initiatePayment() {
    payPending.mutate(
      { billId, payment: { tenderType: 'QRIS' }, idempotencyKey },
      {
        onSuccess: (res) => {
          if (res) setPendingPayment(res)
        },
      },
    )
  }

  function confirmPayment() {
    if (!pendingPayment) return
    capture.mutate(pendingPayment.paymentId, {
      onSuccess: (captured) => {
        if (isCaptureObserved(captured?.status, capturedRef.current)) {
          capturedRef.current = true
          onSuccess({ tenderType: 'QRIS' })
        }
      },
    })
  }

  // ADR 0045: the GATEWAY lifecycle — auto-creates a charge the instant `pendingPayment` exists.
  const gateway = useGatewayQris(session, {
    vertical: 'restaurant',
    paymentId: pendingPayment ? pendingPayment.paymentId : null,
    businessId: session.businessId,
    amountMinor: pendingPayment?.amountMinor ?? chargeMinor,
    currency,
  })

  // ADR 0045: the vertical-read poll — capture never happens client-side for GATEWAY, so this polls
  // the SAME receipt read "Mark as paid" would show, backing off on error and stopping once terminal.
  const receiptQuery = useReceipt(session, pendingPayment ? pendingPayment.paymentId : null, {
    refetchInterval: (query) => pollIntervalFor(query.state.data?.status ?? null, query.state.status === 'error'),
  })

  useEffect(() => {
    if (!pendingPayment) return
    if (isCaptureObserved(receiptQuery.data?.status, capturedRef.current)) {
      capturedRef.current = true
      void qc.invalidateQueries({ queryKey: ['pnl'] })
      onSuccess({ tenderType: 'QRIS' })
    }
  }, [pendingPayment, receiptQuery.data, onSuccess, qc])

  // ADR 0045 (bills): cancels the live charge. A capture-in-flight race (or an already-captured
  // observation) leaves everything as-is — `isCleanCancel` false — the vertical-read poll above is
  // about to hand off to onSuccess. A genuine CLEAN cancel: unlike the order flow, a bill's PENDING
  // payment is here ABANDONED (releasing the reservation) and reset to null, falling back to the
  // initiate view — see billGatewayCapture.ts's class doc for why bills differ from orders here.
  const handleGatewayCancel = useCallback(async (): Promise<boolean> => {
    const capturedInFlight = await gateway.cancel()
    if (!isCleanCancel(capturedInFlight, capturedRef.current)) return true
    if (pendingPayment) abandon.mutate(pendingPayment.paymentId)
    setPendingPayment(null)
    return false
  }, [gateway, pendingPayment, abandon])

  useEffect(() => {
    if (!pendingPayment) return undefined
    registerGatewayCancel(handleGatewayCancel)
    return () => registerGatewayCancel(null)
  }, [pendingPayment, registerGatewayCancel, handleGatewayCancel])

  if (!pendingPayment) {
    return (
      <DigitalInitiateView
        chargeMinor={chargeMinor}
        currency={currency}
        locale={locale}
        busy={payPending.isPending}
        errorSlot={payPending.isError ? <CheckoutErrorText error={payPending.error} /> : null}
        onInitiate={initiatePayment}
        onCancel={onClose}
      />
    )
  }

  return (
    <GatewayQrisPendingView
      amountMinor={pendingPayment.amountMinor}
      currency={currency}
      locale={locale}
      qrString={gateway.charge?.qrString ?? null}
      expiresAtMs={gateway.charge?.expiresAt ? new Date(gateway.charge.expiresAt).getTime() : null}
      phase={gateway.phase}
      errorSlot={
        gateway.phase !== 'error' && receiptQuery.isError ? (
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
