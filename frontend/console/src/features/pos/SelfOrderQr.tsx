/**
 * SelfOrderQr — Phase 6 (ADR 0029) management panel (owner/manager) inside the table-management
 * drawer: one QR code per active table plus one "counter / kiosk" code, all pointing at the
 * anonymous self-order mini app (frontend/self-order, `VITE_SELF_ORDER_URL`). "Rotate" invalidates
 * every printed code for the OUTLET at once (the backend's rotate contract is outlet-scoped, not
 * per-table — see selfOrderApi.ts) — gated behind a confirm dialog since it breaks anything already
 * printed and taped to a table.
 *
 * QR codes are rendered CLIENT-SIDE to <canvas> via the `qrcode` package — the token itself is the
 * only credential (no login for the self-order app), so nothing but the printed sheet needs to
 * leave this screen.
 *
 * Print-friendly: the grid renders inside #native-selforder-qr-print, the print target the global
 * index.css print-isolation rule already recognises (mirroring ThermalReceipt/KotView) — printing
 * shows ONLY the QR sheet on plain paper, forced black-on-white regardless of the console's own
 * light/dark theme (a printed sheet is never themed).
 *
 * Strings rule (rule 9): every label is an i18n key. Table labels / the token itself are business
 * data, not UI copy, and cross verbatim.
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import QRCode from 'qrcode'
import { Printer, QrCode as QrCodeIcon, RefreshCw } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Skeleton } from '@/components/ui/Skeleton'
import type { CompanySession } from '@/lib/session'
import { useSelfOrderAccess, useRotateSelfOrderAccess } from './selfOrderApi'

// Base URL of the separate self-order mini app (frontend/self-order) — the token is appended as
// `?t=`. Defaults to the app's own dev-server port (5174) so a bare local checkout "just works".
const SELF_ORDER_BASE_URL: string = import.meta.env.VITE_SELF_ORDER_URL ?? 'http://localhost:5174'

interface Props {
  session: CompanySession
  outletId: string
}

export function SelfOrderQr({ session, outletId }: Props) {
  const { t } = useTranslation()
  const query = useSelfOrderAccess(session, outletId)
  const rotate = useRotateSelfOrderAccess(session, outletId)
  const [showRotateConfirm, setShowRotateConfirm] = useState(false)

  const kioskToken = query.data?.kioskToken ?? null
  const tableTokens = query.data?.tables ?? []
  const hasAnyToken = kioskToken != null || tableTokens.length > 0

  return (
    <div className="border-t border-line">
      <div className="flex items-center justify-between gap-2 px-5 py-4">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-ink">
          <QrCodeIcon className="size-4 text-ink-3" aria-hidden="true" />
          {t('pos.selfOrderQr.title')}
        </h3>
        {hasAnyToken ? (
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => window.print()}
              aria-label={t('pos.selfOrderQr.print')}
              title={t('pos.selfOrderQr.print')}
              className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <Printer className="size-4" aria-hidden="true" />
            </button>
            <button
              type="button"
              onClick={() => setShowRotateConfirm(true)}
              aria-label={t('pos.selfOrderQr.rotate')}
              title={t('pos.selfOrderQr.rotate')}
              className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <RefreshCw className="size-4" aria-hidden="true" />
            </button>
          </div>
        ) : null}
      </div>

      <p className="px-5 pb-3 text-xs text-ink-3">{t('pos.selfOrderQr.hint')}</p>

      <div className="px-5 pb-5">
        {query.isLoading ? (
          <div className="grid grid-cols-2 gap-3">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="flex flex-col items-center gap-2 rounded-xl border border-line bg-surface p-3">
                <Skeleton className="size-[160px] rounded-lg" />
                <Skeleton className="h-3 w-16" />
              </div>
            ))}
          </div>
        ) : query.isError ? (
          <p className="text-xs text-loss" role="alert">
            {t('pos.selfOrderQr.loadError')}
          </p>
        ) : !hasAnyToken ? (
          <p className="py-4 text-center text-sm text-ink-3">{t('pos.selfOrderQr.empty')}</p>
        ) : (
          <div id="native-selforder-qr-print">
            {/* Forces black-on-white on paper regardless of the console's light/dark theme —
                identical rationale to ThermalReceipt's inline print stylesheet. */}
            <style>{`
              @media print {
                @page { margin: 12mm; }
                #native-selforder-qr-print, #native-selforder-qr-print * {
                  color: #000 !important;
                  background: #fff !important;
                }
              }
            `}</style>
            <div className="grid grid-cols-2 gap-3 print:grid-cols-3 print:gap-6">
              {kioskToken ? <QrCard label={t('pos.selfOrderQr.kioskLabel')} token={kioskToken} /> : null}
              {tableTokens.map((tk) => (
                <QrCard key={tk.tableId} label={tk.tableLabel} token={tk.token} />
              ))}
            </div>
          </div>
        )}
      </div>

      {showRotateConfirm ? (
        <RotateConfirmDialog
          pending={rotate.isPending}
          error={rotate.isError}
          onConfirm={() => {
            rotate.mutate(undefined, { onSuccess: () => setShowRotateConfirm(false) })
          }}
          onClose={() => setShowRotateConfirm(false)}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// QrCard — one printable card (a table label, or the kiosk/counter label)
// ---------------------------------------------------------------------------

function QrCard({ label, token }: { label: string; token: string }) {
  const { t } = useTranslation()
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const url = `${SELF_ORDER_BASE_URL}/?t=${encodeURIComponent(token)}`

  useEffect(() => {
    if (!canvasRef.current) return
    void QRCode.toCanvas(canvasRef.current, url, { width: 160, margin: 1 })
  }, [url])

  return (
    <div className="flex flex-col items-center gap-2 rounded-xl border border-line bg-surface p-3 text-center print:break-inside-avoid print:rounded-none print:border-ink-200 print:p-6">
      <canvas
        ref={canvasRef}
        role="img"
        aria-label={t('pos.selfOrderQr.qrAriaLabel', { label })}
        className="print:h-auto print:w-full print:max-w-[220px]"
      />
      <span className="text-xs font-semibold text-ink">{label}</span>
    </div>
  )
}

// ---------------------------------------------------------------------------
// RotateConfirmDialog
// ---------------------------------------------------------------------------

function RotateConfirmDialog({
  pending,
  error,
  onConfirm,
  onClose,
}: {
  pending: boolean
  error: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  useBackDismiss(onClose, !pending)
  return (
    <div
      className="fixed inset-0 z-[70] grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.selfOrderQr.rotateConfirmTitle')}
    >
      <div className="w-full max-w-sm rounded-[20px] border border-line bg-surface p-6 shadow-xl">
        <p className="text-[15px] font-semibold text-ink">{t('pos.selfOrderQr.rotateConfirmTitle')}</p>
        <p className="mt-2 text-sm text-ink-3">{t('pos.selfOrderQr.rotateConfirmBody')}</p>
        {error ? (
          <p className="mt-3 text-xs text-loss" role="alert">
            {t('pos.selfOrderQr.rotateError')}
          </p>
        ) : null}
        <div className="mt-5 flex gap-2">
          <Button variant="outline" className="flex-1" onClick={onClose} disabled={pending}>
            {t('common.cancel')}
          </Button>
          <Button className="flex-1" onClick={onConfirm} disabled={pending}>
            {pending ? <Spinner /> : t('pos.selfOrderQr.rotateConfirmAction')}
          </Button>
        </div>
      </div>
    </div>
  )
}
