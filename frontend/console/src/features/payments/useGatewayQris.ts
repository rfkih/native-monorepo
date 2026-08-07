/**
 * useGatewayQris — the adapter-side GATEWAY-QRIS lifecycle hook (ADR 0045). Each vertical adapter
 * (features/pos's `RestaurantDigitalAttempt`, features/servicepos's `ServiceDigitalAttempt`) owns
 * ONE instance of this per digital-tender attempt: it auto-creates the dynamic-QR charge the moment
 * the vertical's own PENDING payment exists, drives the `chargePhase.ts` reducer off the
 * create/sync/cancel responses plus a client-side 1s expiry ticker, and exposes just enough surface
 * for `QrisPanelViews.tsx`'s `GatewayQrisPendingView` to render.
 *
 * What this hook does NOT do: poll for capture. Capture is a vertical-side write (the
 * `PaymentChargeSucceeded` consumer calling the SAME capture writer "Mark as paid" uses) — the
 * adapter polls its OWN payment/ticket read for that (see `pollIntervalFor.ts`) and calls the
 * shared `onSuccess` once it observes CAPTURED. This hook only tracks the CHARGE's own lifecycle.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import type { CompanySession } from '@/lib/session'
import { cancelCharge, createCharge, syncCharge, type ChargeResponse } from './api'
import { nextChargePhase, type ChargePhase, type ChargePhaseEvent } from './chargePhase'

export interface UseGatewayQrisParams {
  vertical: 'restaurant' | 'carwash' | 'barbershop'
  /**
   * The vertical's own PENDING payment id — auto-creates the charge the instant this transitions
   * from null to a value (ref-guarded: a re-render with the SAME id, or React StrictMode's
   * double-invoke, never mints a second attempt). Set back to null (e.g. the adapter falls back to
   * MANUAL) to arm the guard for a possible future payment.
   */
  paymentId: string | null
  /** The ticket id for carwash/barbershop; omit/null for restaurant. */
  referenceId?: string | null
  businessId: string
  /** The tender residual — integer minor units (rule 8); re-read on every create/retry call, so a
   * caller that recomputes it between renders always charges the CURRENT amount. */
  amountMinor: number
  currency: string
}

export interface UseGatewayQrisResult {
  charge: ChargeResponse | null
  phase: ChargePhase
  /** Mints a fresh attempt (a new `charge:<paymentId>:<attempt>` Idempotency-Key) and creates a new
   * charge — the `expired` panel's "new QR" action AND the `error` panel's "retry" action (both are
   * the same operation: ask for a brand new charge). */
  retryOrNewQr: () => void
  /**
   * Cancels the live charge at Midtrans. Resolves `true` when the response comes back SUCCEEDED —
   * a capture-in-flight race where the customer paid WHILE the cancel was in transit. The CALLER
   * must treat `true` as "do not close/fall back — keep polling the vertical read toward the
   * receipt" (captured beats cancel, by design — see ADR 0045 and `chargePhase.ts`). Resolves
   * `false` on a clean cancel OR if the cancel call itself failed — either way, safe to fall back to
   * the manual "Mark as paid" flow.
   */
  cancel: () => Promise<boolean>
  /** POST .../sync — the manual "customer says they paid" status probe (the "check status" tap). */
  checkStatus: () => void
}

export function useGatewayQris(
  session: CompanySession,
  params: UseGatewayQrisParams,
): UseGatewayQrisResult {
  const [charge, setCharge] = useState<ChargeResponse | null>(null)
  const [phase, setPhase] = useState<ChargePhase>('idle')
  const attemptRef = useRef(0)
  const createdForRef = useRef<string | null>(null)

  const dispatch = useCallback((event: ChargePhaseEvent) => setPhase((p) => nextChargePhase(p, event)), [])

  const create = useCallback(async () => {
    if (!params.paymentId) return
    attemptRef.current += 1
    const idempotencyKey = `charge:${params.paymentId}:${attemptRef.current}`
    dispatch({ type: 'CREATE_STARTED' })
    try {
      const res = await createCharge(session, {
        idempotencyKey,
        vertical: params.vertical,
        paymentId: params.paymentId,
        referenceId: params.referenceId ?? null,
        businessId: params.businessId,
        amountMinor: params.amountMinor,
        currency: params.currency,
      })
      if (!res) {
        dispatch({ type: 'CREATE_FAILED' })
        return
      }
      setCharge(res)
      dispatch({ type: 'CHARGE_STATUS', status: res.status })
    } catch {
      dispatch({ type: 'CREATE_FAILED' })
    }
  }, [
    session,
    params.vertical,
    params.paymentId,
    params.referenceId,
    params.businessId,
    params.amountMinor,
    params.currency,
    dispatch,
  ])

  // Auto-create exactly once per PENDING payment id.
  useEffect(() => {
    if (params.paymentId && createdForRef.current !== params.paymentId) {
      createdForRef.current = params.paymentId
      attemptRef.current = 0
      void create()
    }
    if (!params.paymentId) createdForRef.current = null
    // `create` is intentionally excluded — it is recreated whenever any param changes (e.g. the
    // residual amount ticking as a gift card is applied), but the auto-create effect must fire
    // ONLY on a paymentId transition, never on an amount recompute for the SAME attempt.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.paymentId])

  // Client-side expiry — 1s resolution, only while a QR is actually live on screen.
  useEffect(() => {
    if (phase !== 'active' || !charge?.expiresAt) return undefined
    const expiresAtMs = new Date(charge.expiresAt).getTime()
    const id = setInterval(() => {
      if (Date.now() >= expiresAtMs) dispatch({ type: 'COUNTDOWN_EXPIRED' })
    }, 1000)
    return () => clearInterval(id)
  }, [phase, charge?.expiresAt, dispatch])

  const retryOrNewQr = useCallback(() => {
    void create()
  }, [create])

  const checkStatus = useCallback(() => {
    if (!charge) return
    void (async () => {
      try {
        const res = await syncCharge(session, charge.chargeId)
        if (!res) return
        setCharge(res)
        dispatch({ type: 'CHARGE_STATUS', status: res.status })
      } catch {
        // A transient sync failure isn't fatal — the expiry ticker and the vertical-read poll
        // still drive the panel forward; the cashier can just tap "check status" again.
      }
    })()
  }, [session, charge, dispatch])

  const cancel = useCallback(async (): Promise<boolean> => {
    if (!charge) return false
    dispatch({ type: 'CANCEL_STARTED' })
    try {
      const res = await cancelCharge(session, charge.chargeId)
      if (!res) {
        dispatch({ type: 'CANCEL_FAILED' })
        return false
      }
      setCharge(res)
      dispatch({ type: 'CHARGE_STATUS', status: res.status })
      return res.status === 'SUCCEEDED'
    } catch {
      dispatch({ type: 'CANCEL_FAILED' })
      return false
    }
  }, [session, charge, dispatch])

  return { charge, phase, retryOrNewQr, cancel, checkStatus }
}
