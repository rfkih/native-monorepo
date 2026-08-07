/**
 * QrisPanelViews — the GATEWAY dynamic-QRIS panel views (ADR 0045). Stateless (ADR 0034: no
 * fetching, no vertical types — the adapter holds the `useGatewayQris` lifecycle and passes
 * primitives down), mirroring `DigitalPanelViews.tsx`'s split between adapter-owned behavior and
 * pos-shell-owned markup.
 *
 * `ChargePhaseLike` is a STRUCTURAL mirror of `features/payments/chargePhase.ts`'s `ChargePhase` —
 * pos-shell does not import payments types (the `channelPicker.ts` `SalesChannelLike` precedent:
 * "either side can evolve independently" as long as the shape matches).
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import QRCode from 'qrcode'
import { RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { formatCountdown, msLeft } from './qrisTime'

export type ChargePhaseLike = 'idle' | 'creating' | 'active' | 'expired' | 'error' | 'cancelling'

/**
 * Renders `value` as a QR code onto a `<canvas>` via the already-installed `qrcode` package —
 * the same client-side-render idiom `features/pos/SelfOrderQr.tsx`'s `QrCard` uses. Exported so
 * both the till panel below AND the customer display (`features/pos/display/CustomerDisplay.tsx`)
 * render an identical QR from the same `qrString`.
 */
export function QrCanvas({
  value,
  size = 220,
  ariaLabel,
  className,
}: {
  value: string
  size?: number
  ariaLabel: string
  className?: string
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    if (!canvasRef.current) return
    void QRCode.toCanvas(canvasRef.current, value, { width: size, margin: 1 })
  }, [value, size])

  return <canvas ref={canvasRef} role="img" aria-label={ariaLabel} className={className} />
}

export function GatewayQrisPendingView({
  amountMinor,
  currency,
  locale,
  qrString,
  expiresAtMs,
  phase,
  errorSlot,
  onCancel,
  onNewQr,
  onCheckStatus,
  onManualConfirm,
  manualConfirmBusy,
}: {
  amountMinor: number
  currency: string
  locale: string
  qrString: string | null
  expiresAtMs: number | null
  phase: ChargePhaseLike
  errorSlot?: React.ReactNode
  onCancel: () => void
  onNewQr: () => void
  onCheckStatus: () => void
  /** The manual "Mark as paid" override — stays available during GATEWAY too (idempotent
   * server-side, so a race with the webhook is harmless — ADR 0045). Undefined hides it. */
  onManualConfirm?: () => void
  manualConfirmBusy?: boolean
}) {
  const { t } = useTranslation()

  // Ticks the visible mm:ss chip once a second while a QR is actually live — the phase itself
  // (and whether the panel should treat the charge as expired) is decided by the adapter's
  // `useGatewayQris` ticker; this is presentation-only re-rendering, not a data dependency.
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (phase !== 'active' || expiresAtMs == null) return undefined
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [phase, expiresAtMs])

  if (phase === 'creating' || phase === 'idle') {
    return (
      <div className="px-5 pb-5">
        <div className="flex flex-col items-center gap-3 py-8">
          <Spinner className="text-brand-600" />
          <p className="text-sm text-ink-3">{t('pos.payment.qris.gatewayWaiting')}</p>
        </div>
      </div>
    )
  }

  if (phase === 'error') {
    return (
      <div className="px-5 pb-5">
        {errorSlot ?? (
          <p className="mb-3 text-xs text-loss" role="alert">
            {t('pos.payment.qris.chargeError')}
          </p>
        )}
        <Button className="w-full" onClick={onNewQr}>
          <RefreshCw className="size-4" aria-hidden />
          {t('pos.payment.qris.newQr')}
        </Button>
        <Button variant="ghost" className="mt-2 w-full text-xs" onClick={onCancel}>
          {t('pos.payment.cancel')}
        </Button>
      </div>
    )
  }

  const expired = phase === 'expired'
  const cancelling = phase === 'cancelling'
  const remainingMs = expiresAtMs != null ? msLeft(expiresAtMs, now) : 0

  return (
    <div className="px-5 pb-5">
      <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3 text-sm text-amber-2">
        <div className="flex items-center justify-between gap-2">
          <Badge tone="amber">{t('pos.payment.tenderQris')}</Badge>
          {expired ? (
            <span className="text-xs font-semibold text-loss">{t('pos.payment.qris.expired')}</span>
          ) : (
            <span className="tnum font-mono text-xs font-semibold text-amber-2">
              {t('pos.payment.qris.expiresIn', { time: formatCountdown(remainingMs) })}
            </span>
          )}
        </div>
        <p className="mt-1 leading-relaxed">{t('pos.payment.qris.gatewayInitiateHint')}</p>
      </div>

      {errorSlot}

      {qrString ? (
        <div className={cn('mb-4 flex justify-center', expired && 'opacity-40 grayscale')}>
          <QrCanvas
            value={qrString}
            size={220}
            ariaLabel={t('pos.payment.qris.scanToPay')}
            className="rounded-xl border border-line bg-white p-2"
          />
        </div>
      ) : null}

      <div className="mb-4 flex items-baseline justify-between rounded-lg border border-line bg-paper px-4 py-3">
        <span className="text-sm text-ink-3">{t('pos.payment.pending')}</span>
        <span className="tnum font-mono text-lg font-medium text-ink">
          {formatMoney(amountMinor, currency, locale)}
        </span>
      </div>

      {expired ? (
        <Button className="w-full" onClick={onNewQr}>
          <RefreshCw className="size-4" aria-hidden />
          {t('pos.payment.qris.newQr')}
        </Button>
      ) : (
        <>
          <Button
            variant="outline"
            className="w-full text-xs"
            disabled={cancelling}
            onClick={onCheckStatus}
          >
            {t('pos.payment.qris.checkStatus')}
          </Button>
          {onManualConfirm ? (
            <Button
              className="mt-2 w-full"
              disabled={cancelling || manualConfirmBusy}
              onClick={onManualConfirm}
            >
              {manualConfirmBusy ? <Spinner /> : t('pos.payment.markAsPaid')}
            </Button>
          ) : null}
        </>
      )}

      <Button variant="ghost" className="mt-2 w-full text-xs" disabled={cancelling} onClick={onCancel}>
        {cancelling ? <Spinner /> : t('pos.payment.cancel')}
      </Button>
    </div>
  )
}
