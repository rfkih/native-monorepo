/**
 * ThermalReceipt — a shared, realistic thermal-paper receipt component.
 *
 * Used for BOTH on-screen display (inside the dark modal backdrop) and
 * window.print() output (WYSIWYG). The separate hidden PrintReceipt
 * sub-components in ReceiptView and BillReceiptView are replaced by this.
 *
 * Design goals
 * ──────────────
 * - Narrow white "paper" strip (~320 px) on the dark backdrop, with
 *   torn/perforated top and bottom edges (CSS repeating-linear-gradient mask).
 * - Monospace everywhere (JetBrains Mono via font-mono). ~12–13 px, tight leading.
 * - Dashed horizontal rules between sections (classic thermal look).
 * - Item rows: qty × name with a dotted-leader baseline; price right-aligned.
 * - Totals table: label left, amount right, tabular figures.
 * - Footer: "Thank you" line + a decorative CSS barcode + reference number.
 * - Always black ink on white paper — never themed (a receipt IS white paper).
 *
 * Print (@media print)
 * ──────────────────────
 * Only the paper strip is visible; backdrop, buttons, and torn-edge decoration
 * are hidden. @page rule: 80 mm wide, auto height.
 *
 * Strings rule: all user-facing strings go through the `t` prop — no hardcoded copy.
 * Money rule: all amounts come in as pre-formatted label strings (callers use formatMoney).
 */

import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Printer, RefreshCw, TriangleAlert, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { Button } from '@/components/ui/Button'
import { usePrinter } from '@/lib/escpos/printerContext'
import { toEscposReceiptData } from '@/lib/escpos/fromThermalProps'
import { cn } from '@/lib/cn'

// ---------------------------------------------------------------------------
// Prop types — normalised data model
// ---------------------------------------------------------------------------

export interface ThermalModifier {
  label: string
  /** Formatted delta string, e.g. "+Rp 2.000". Omit for free modifiers. */
  deltaLabel?: string
}

export interface ThermalLineItem {
  qty: number
  name: string
  /** Formatted line total, e.g. "Rp 25.000". */
  priceLabel: string
  modifiers: ThermalModifier[]
}

export interface ThermalRow {
  label: string
  valueLabel: string
  /** When true the value is styled as a loss/reduction (e.g. discount). */
  isDeduction?: boolean
}

/**
 * An optional DESTRUCTIVE action rendered as a full-width button beneath the main Print/action row
 * (e.g. the manager-gated "Return sale" refund, ADR 0061). Omitted → nothing renders, so every
 * caller that doesn't pass one is byte-identical to before.
 */
export interface ThermalSecondaryAction {
  label: string
  onClick: () => void
  disabled?: boolean
  /**
   * Visual tone. Defaults to `'danger'` (red) — the original use was the manager-gated Return sale
   * (ADR 0061). `'neutral'` is a plain outline for a non-destructive drill-in (e.g. the past-day
   * "Lihat transaksi" from the closing-history Z-report).
   */
  tone?: 'neutral' | 'danger'
}

export interface ThermalProps {
  /** Business / outlet name — printed in ALL CAPS in the header. */
  businessName: string
  /** Receipt title shown beneath the business name, e.g. "RECEIPT" or "BILL RECEIPT". */
  title: string
  /** Short reference printed in meta and footer barcode area, e.g. last 8 chars of an id. */
  reference: string
  /** Optional tagline line (order type, guest label, etc.). */
  tagline?: string
  /** Formatted date+time string, already locale-formatted by the caller. */
  dateTime: string
  /** Rows between the header and the item list (order type, table, etc.). */
  metaRows: ThermalRow[]
  lineItems: ThermalLineItem[]
  /** Rows between items and grand total (subtotal, discount, service, tax). */
  totalRows: ThermalRow[]
  /** Formatted grand total — displayed larger and bolder. */
  grandTotalLabel: string
  /** Payment detail rows (tender type, tendered, change, status). */
  paymentRows: ThermalRow[]
  /** Footer "thank you" note. */
  footerNote: string
  /** Called when the operator taps Print. */
  onPrint: () => void
  /** Called when the operator taps the primary CTA (New order / Close). */
  onAction: () => void
  /** Label for the primary action button. */
  actionLabel: string
  /** Optional destructive action shown full-width below the Print/primary row — see the type doc. */
  secondaryAction?: ThermalSecondaryAction
  /** When true, shows an amber "pending" banner beneath the title. */
  isPending?: boolean
  /** Pending note text (shown when isPending is true). */
  pendingNote?: string
  /**
   * Phase 5 (ADR 0028): when true, this receipt was generated CLIENT-SIDE for an offline sale —
   * the totals are a provisional estimate (provisionalPricing.ts) and the sale itself is still
   * queued for replay. Renders a solid (not dashed) marker so it stands out on both screen and the
   * printed thermal paper — a cashier must never mistake this for a server-confirmed receipt.
   */
  isProvisional?: boolean
  /** Provisional marker text (shown when isProvisional is true). */
  provisionalNote?: string
  /**
   * True when the underlying payment was reversed (VOIDED / REFUNDED / PARTIALLY_REFUNDED) — a
   * cancelled or refunded sale must never be mistaken for a normal paid receipt, on screen OR on
   * the printed thermal paper (incl. a history reprint). Renders a solid banner identical in style
   * to the provisional marker, placed right after it (and after the pending banner).
   */
  isReversed?: boolean
  /** Reversed-sale banner text (shown when isReversed is true). */
  reversedNote?: string
  /**
   * Set by surfaces that mount right after a successful payment (ReceiptView, ServiceReceipt,
   * BillReceiptView): when the operator has enabled auto-print in printer settings AND a device
   * is connected, the receipt is sent to it once on appearance — no Print tap. Never auto-falls
   * back to window.print() (an OS dialog popping unprompted after every sale would be worse than
   * no auto-print); on a device failure the Print button remains the manual recovery.
   */
  autoPrint?: boolean
  /**
   * True for a CASH sale — gates the cash-drawer kick (a card/QRIS/other-tender receipt must never
   * pop the drawer, even when the device toggle is on; see usePrinter's printReceipt doc). Omitted
   * defaults to true (the historical behavior) so an un-migrated caller keeps working.
   */
  cashTender?: boolean
  /**
   * Stacking override for surfaces that mount over another z-50 overlay (same idea as
   * PaymentSurfaceFrame's zIndexClass): BillReceiptView passes z-[70] so a check receipt sits
   * above the z-50 bill sheet like KotView does. Defaults to the historical z-40.
   */
  zIndexClass?: string
}

// ---------------------------------------------------------------------------
// Dashed rule — the classic thermal receipt separator
// ---------------------------------------------------------------------------

function DashedRule() {
  return (
    <div
      aria-hidden="true"
      style={{
        borderTop: '1px dashed #000',
        margin: '8px 0',
      }}
    />
  )
}

// ---------------------------------------------------------------------------
// Item row with dotted leader
// ---------------------------------------------------------------------------

function ItemRow({ qty, name, priceLabel }: { qty: number; name: string; priceLabel: string }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'baseline',
        gap: 4,
        lineHeight: 1.4,
        marginBottom: 2,
      }}
    >
      {/* Qty badge */}
      <span style={{ minWidth: 20, fontWeight: 700, flexShrink: 0 }}>{qty}×</span>
      {/* Name + dotted leader */}
      <span
        style={{
          flex: 1,
          overflow: 'hidden',
          whiteSpace: 'nowrap',
          // Dotted leader via repeating gradient on the bottom border
          backgroundImage: 'linear-gradient(to right, #000 25%, transparent 0%)',
          backgroundPosition: '0 bottom',
          backgroundSize: '4px 1px',
          backgroundRepeat: 'repeat-x',
          paddingBottom: 1,
        }}
      >
        {name}
      </span>
      {/* Price — right-aligned, sits on top of the leader */}
      <span
        style={{
          flexShrink: 0,
          textAlign: 'right',
          fontVariantNumeric: 'tabular-nums',
          marginLeft: 4,
          backgroundColor: '#fff', // mask the dotted leader behind the price
          paddingLeft: 2,
        }}
      >
        {priceLabel}
      </span>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Label / value row (totals, payment details, meta)
// ---------------------------------------------------------------------------

function LabelValueRow({
  label,
  value,
  bold,
  isDeduction,
  large,
}: {
  label: string
  value: string
  bold?: boolean
  isDeduction?: boolean
  large?: boolean
}) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'baseline',
        margin: '2px 0',
        fontSize: large ? 15 : 12,
        fontWeight: bold ? 700 : 400,
      }}
    >
      <span>{label}</span>
      <span
        style={{
          fontVariantNumeric: 'tabular-nums',
          color: isDeduction ? '#000' : undefined,
        }}
      >
        {value}
      </span>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Torn / perforated edge — CSS mask via repeating-linear-gradient
// Only shown on-screen (print:hidden equivalent).
// ---------------------------------------------------------------------------

function TornEdge({ position }: { position: 'top' | 'bottom' }) {
  const isTop = position === 'top'
  // A row of semicircles punched out of the paper edge — simulates perforation.
  // We use repeating-linear-gradient to create a zigzag/scallop pattern.
  return (
    <div
      aria-hidden="true"
      className="print:hidden"
      style={{
        height: 12,
        width: '100%',
        backgroundImage: isTop
          ? 'radial-gradient(circle at 50% 0%, transparent 8px, #fff 8px)'
          : 'radial-gradient(circle at 50% 100%, transparent 8px, #fff 8px)',
        backgroundSize: '20px 12px',
        backgroundRepeat: 'repeat-x',
        backgroundPosition: isTop ? '0 0' : '0 100%',
        // On the top edge the scallops cut into the paper from above,
        // so the backdrop colour (transparent) shows through.
        flexShrink: 0,
      }}
    />
  )
}

// ---------------------------------------------------------------------------
// Main ThermalReceipt component
// ---------------------------------------------------------------------------

// Inline styles used throughout so that @media print picks them up without
// relying on Tailwind utility classes being transformed by the print variant.
// The outer wrapper uses Tailwind for the backdrop/scroll behaviour only.

const paperStyle: React.CSSProperties = {
  fontFamily: "'JetBrains Mono', 'Courier New', Courier, monospace",
  fontSize: 12,
  lineHeight: 1.45,
  color: '#000',
  backgroundColor: '#fff',
  width: 320,
  maxWidth: '100%',
  // Drop shadow — skipped inside @media print
  boxShadow: '0 4px 32px rgba(0,0,0,0.35), 0 1px 4px rgba(0,0,0,0.2)',
}

const paperBodyStyle: React.CSSProperties = {
  padding: '4px 20px 12px',
}

export function ThermalReceipt({
  businessName,
  title,
  reference,
  tagline,
  dateTime,
  metaRows,
  lineItems,
  totalRows,
  grandTotalLabel,
  paymentRows,
  footerNote,
  onPrint,
  onAction,
  actionLabel,
  secondaryAction,
  isPending,
  pendingNote,
  isProvisional,
  provisionalNote,
  isReversed,
  reversedNote,
  autoPrint,
  cashTender,
  zIndexClass = 'z-40',
}: ThermalProps) {
  const { t } = useTranslation()
  // No dedicated close/X or Escape handling on this surface — onAction IS the dismiss/proceed
  // callback every caller passes (labelled Close / New order), so Back mirrors it.
  useBackDismiss(onAction)
  useScrollLock()
  const headingId = useId()
  const printer = usePrinter()
  const [deviceBusy, setDeviceBusy] = useState(false)
  // Recovery UI (P1 flow hardening): a silent device failure used to just fall back to
  // window.print() with no explanation. `notice` names WHY, and stays actionable (Retry) right
  // here — never forcing a trip to /settings/printer mid-sale.
  const [notice, setNotice] = useState<'autoFailed' | 'fallback' | null>(null)
  const [recovering, setRecovering] = useState(false)

  const escposData = useCallback(
    () =>
      toEscposReceiptData({
        businessName,
        title,
        reference,
        tagline,
        dateTime,
        metaRows,
        lineItems,
        totalRows,
        grandTotalLabel,
        grandTotalCaption: t('pos.receipt.total'),
        paymentRows,
        footerNote,
        provisionalNote: isProvisional ? provisionalNote : undefined,
        reversedNote: isReversed ? reversedNote : undefined,
      }),
    [
      businessName,
      title,
      reference,
      tagline,
      dateTime,
      metaRows,
      lineItems,
      totalRows,
      grandTotalLabel,
      t,
      paymentRows,
      footerNote,
      isProvisional,
      provisionalNote,
      isReversed,
      reversedNote,
    ],
  )

  // Auto-print exactly once per receipt appearance (see the autoPrint prop doc). The ref guard —
  // not the dep list — is what enforces "once": the printer context value is a fresh object every
  // provider render, so the effect re-runs; every rerun after the first is a no-op. The timer
  // defers the dispatch (and its setState) out of the effect body per react-hooks rules.
  // RawBT caveat: with the "Server for RawBT" companion app its write goes over a local WebSocket
  // (silent, no activation needed); WITHOUT it the intent:-URL fallback is gated on transient user
  // activation (~5 s from the confirm tap) — a slow online capture can outlive the window, the
  // blocked navigation is silent, and autoPrinted stays latched; the Print button is the recovery.
  const autoPrinted = useRef(false)
  useEffect(() => {
    if (!autoPrint || autoPrinted.current) return
    // The toggle must be on, but a LIVE connection is deliberately NOT required (field-found on
    // the Android till): idle SPP printers drop their socket between sales, and the mount-time
    // re-attach can lose the race with a fast first sale — printReceipt now self-heals from the
    // saved config, and a genuine failure surfaces via the autoFailed notice below.
    if (!printer.config?.autoPrint) return
    const timer = setTimeout(() => {
      if (autoPrinted.current) return
      autoPrinted.current = true
      setDeviceBusy(true)
      void printer
        .printReceipt(escposData(), { cashTender: cashTender ?? true })
        .then((ok) => {
          // Deliberately still no window.print() fallback here (an OS dialog popping unprompted
          // after every sale would be worse than none) — but the operator must be TOLD, not left
          // to notice a missing receipt on their own. The banner's Retry stays right on this screen.
          if (!ok) setNotice('autoFailed')
        })
        .finally(() => setDeviceBusy(false))
    }, 0)
    return () => clearTimeout(timer)
  }, [autoPrint, printer, escposData, cashTender])

  /**
   * Print handler (ADR 0039): when a thermal printer is connected, send raw ESC/POS bytes to it
   * (silent, no OS dialog). Otherwise — or if the device write fails — fall back to the browser's
   * window.print() (the WYSIWYG paper), which is exactly the previous behavior. The `onPrint` prop
   * IS that browser path (callers pass window.print), so it doubles as the fallback. A failed
   * DEVICE attempt (as opposed to simply having no device configured) now surfaces `notice` so the
   * fallback dialog isn't a silent surprise.
   */
  const handlePrint = async () => {
    autoPrinted.current = true // a manual tap also cancels a not-yet-fired auto-print
    // Attempt the device whenever a printer is CONFIGURED, connected or not — printReceipt
    // self-heals a dropped link from the saved config (same reasoning as the auto-print gate).
    if (printer.connected || printer.config) {
      setDeviceBusy(true)
      const ok = await printer.printReceipt(escposData(), { cashTender: cashTender ?? true })
      setDeviceBusy(false)
      if (ok) {
        setNotice(null)
        return
      }
      setNotice('fallback')
    }
    onPrint()
  }

  /**
   * The banner's Retry action: re-attempts the SAVED connection right here (see usePrinter's
   * `reconnect` doc — BLE legitimately reopens its chooser because THIS is the user gesture), then
   * immediately re-sends the receipt if that succeeded. Clears the notice only on an actual
   * successful print, never optimistically.
   */
  const retryDevice = async () => {
    setRecovering(true)
    try {
      const reattached = await printer.reconnect()
      if (!reattached) return
      setDeviceBusy(true)
      const ok = await printer.printReceipt(escposData(), { cashTender: cashTender ?? true })
      setDeviceBusy(false)
      if (ok) setNotice(null)
    } finally {
      setRecovering(false)
    }
  }

  return (
    <>
      {/* ------------------------------------------------------------------ */}
      {/* Print stylesheet — injected inline so it survives bundler transforms */}
      {/* ------------------------------------------------------------------ */}
      <style>{`
        /* Print ISOLATION is global (index.css) via visibility — a display:none ancestor
           would blank this nested paper. Here we only tweak the paper itself for print.
           The 80mm @page rule lives HERE (not in index.css) so the statements can print
           on normal paper — each print surface owns its own page size. */
        @media print {
          @page { size: 80mm auto; margin: 6mm; }
          #native-thermal-receipt-paper { width: auto !important; max-width: 100% !important; }
          #native-thermal-receipt-paper .torn-edge { display: none !important; }
        }
        #native-thermal-receipt-paper {
          font-family: 'JetBrains Mono', 'Courier New', Courier, monospace !important;
          font-size: 12px !important;
          color: #000 !important;
          background: #fff !important;
        }
      `}</style>

      {/* ------------------------------------------------------------------ */}
      {/* Backdrop + scroll container (screen only)                           */}
      {/* ------------------------------------------------------------------ */}
      <div
        className={cn(
          'fixed inset-0 grid place-items-start justify-center overflow-y-auto bg-black/60 py-8 px-4 backdrop-blur-sm',
          zIndexClass,
        )}
        role="dialog"
        aria-modal="true"
        aria-labelledby={headingId}
      >
        {/* Paper strip */}
        <div className="reveal" style={paperStyle} id="native-thermal-receipt-paper">
          {/* Torn top edge */}
          <div className="torn-edge">
            <TornEdge position="top" />
          </div>

          {/* Paper body */}
          <div style={paperBodyStyle}>
            {/* ---- HEADER ---- */}
            <div style={{ textAlign: 'center', paddingTop: 8, paddingBottom: 4 }}>
              <div style={{ fontSize: 15, fontWeight: 700, letterSpacing: '0.08em' }}>
                {businessName.toUpperCase()}
              </div>
              {tagline ? (
                <div style={{ fontSize: 11, marginTop: 2 }}>{tagline}</div>
              ) : null}
              <div style={{ fontSize: 11, marginTop: 4, color: '#444' }}>{dateTime}</div>
            </div>

            <DashedRule />

            {/* ---- TITLE + REF ---- */}
            <div style={{ textAlign: 'center', marginBottom: 6 }}>
              <div id={headingId} style={{ fontWeight: 700, letterSpacing: '0.05em' }}>
                {title.toUpperCase()}
              </div>
              <div style={{ fontSize: 11, color: '#444', marginTop: 2 }}>
                {t('pos.receipt.ref')} #{reference}
              </div>
            </div>

            {/* ---- PROVISIONAL BANNER (Phase 5 offline mode, ADR 0028) ---- */}
            {isProvisional && provisionalNote ? (
              <div
                style={{
                  border: '2px solid #000',
                  padding: '4px 6px',
                  marginBottom: 8,
                  fontSize: 11,
                  fontWeight: 700,
                  lineHeight: 1.4,
                  textAlign: 'center',
                  letterSpacing: '0.03em',
                }}
              >
                {provisionalNote}
              </div>
            ) : null}

            {/* ---- PENDING BANNER ---- */}
            {isPending && pendingNote ? (
              <div
                style={{
                  border: '1px dashed #000',
                  padding: '4px 6px',
                  marginBottom: 8,
                  fontSize: 11,
                  lineHeight: 1.4,
                  textAlign: 'center',
                }}
              >
                {pendingNote}
              </div>
            ) : null}

            {/* ---- REVERSED BANNER (voided/refunded/partially refunded) ---- */}
            {isReversed && reversedNote ? (
              <div
                style={{
                  border: '2px solid #000',
                  padding: '4px 6px',
                  marginBottom: 8,
                  fontSize: 11,
                  fontWeight: 700,
                  lineHeight: 1.4,
                  textAlign: 'center',
                  letterSpacing: '0.03em',
                }}
              >
                {reversedNote}
              </div>
            ) : null}

            {/* ---- META ROWS ---- */}
            {metaRows.length > 0 ? (
              <>
                {metaRows.map((row, i) => (
                  <LabelValueRow key={i} label={row.label} value={row.valueLabel} />
                ))}
                <DashedRule />
              </>
            ) : null}

            {/* ---- LINE ITEMS ---- */}
            {/* An item-less receipt (the daily summary / Z-report reuses this component with no line
                items) skips the whole items block AND its trailing rule so the header/totals don't
                sit between two adjacent dashed rules. A real receipt always has ≥1 item. */}
            {lineItems.length > 0 ? (
              <>
                <div style={{ marginBottom: 4 }}>
                  {lineItems.map((item, i) => (
                    <div key={i} style={{ marginBottom: 4 }}>
                      <ItemRow qty={item.qty} name={item.name} priceLabel={item.priceLabel} />
                      {item.modifiers.map((mod, j) => (
                        <div
                          key={j}
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            paddingLeft: 24,
                            fontSize: 11,
                            color: '#444',
                            marginBottom: 1,
                          }}
                        >
                          <span>{mod.label}</span>
                          {mod.deltaLabel ? (
                            <span style={{ fontVariantNumeric: 'tabular-nums' }}>{mod.deltaLabel}</span>
                          ) : null}
                        </div>
                      ))}
                    </div>
                  ))}
                </div>

                <DashedRule />
              </>
            ) : null}

            {/* ---- TOTAL ROWS (subtotal, discount, service, tax) ---- */}
            {totalRows.map((row, i) => (
              <LabelValueRow
                key={i}
                label={row.label}
                value={row.valueLabel}
                isDeduction={row.isDeduction}
              />
            ))}

            <DashedRule />

            {/* ---- GRAND TOTAL ---- */}
            <LabelValueRow
              label={t('pos.receipt.total').toUpperCase()}
              value={grandTotalLabel}
              bold
              large
            />

            <DashedRule />

            {/* ---- PAYMENT ROWS ---- */}
            {paymentRows.map((row, i) => (
              <LabelValueRow key={i} label={row.label} value={row.valueLabel} />
            ))}

            <DashedRule />

            {/* ---- FOOTER ---- */}
            <div style={{ textAlign: 'center', paddingTop: 4 }}>
              <div style={{ fontWeight: 700, letterSpacing: '0.03em', fontSize: 13 }}>
                {footerNote}
              </div>
              {/* Barcode + repeated reference removed (paper efficiency, user request): the
                  barcode was decorative-only and the reference already prints in the header. */}
              <div style={{ fontSize: 10, color: '#888', marginTop: 6 }}>
                {t('pos.receipt.poweredBy')}
              </div>
            </div>
          </div>

          {/* Torn bottom edge */}
          <div className="torn-edge">
            <TornEdge position="bottom" />
          </div>
        </div>

        {/* ---------------------------------------------------------------- */}
        {/* Recovery banner — a failed device print used to fail SILENTLY (a  */}
        {/* window.print() fallback with no explanation, or nothing at all   */}
        {/* for a failed auto-print). Now it's named + actionable right here. */}
        {/* ---------------------------------------------------------------- */}
        {notice ? (
          <div
            role="status"
            className="mt-4 flex w-full items-start gap-2.5 rounded-xl border border-amber-400/30 bg-amber-500/10 px-4 py-3 text-left text-[13px] leading-relaxed text-amber-100 print:hidden"
            style={{ maxWidth: 320 }}
          >
            <TriangleAlert className="mt-0.5 size-4 shrink-0 text-amber-300" />
            <div className="min-w-0 flex-1">
              <p>{t(notice === 'autoFailed' ? 'pos.receipt.autoPrintFailed' : 'pos.receipt.printFallback')}</p>
              <button
                type="button"
                onClick={() => void retryDevice()}
                disabled={recovering || deviceBusy}
                className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-amber-400/40 bg-amber-500/10 px-2.5 py-1.5 text-xs font-semibold text-amber-100 transition-colors hover:bg-amber-500/20 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-300 disabled:cursor-not-allowed disabled:opacity-60"
              >
                <RefreshCw className={cn('size-3.5', recovering && 'animate-spin motion-reduce:animate-none')} />
                {recovering ? t('pos.receipt.reconnecting') : t('common.retry')}
              </button>
            </div>
            <button
              type="button"
              onClick={() => setNotice(null)}
              aria-label={t('common.close')}
              className="shrink-0 rounded-lg p-1 text-amber-300/70 transition-colors hover:text-amber-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-300"
            >
              <X className="size-3.5" />
            </button>
          </div>
        ) : null}

        {/* ---------------------------------------------------------------- */}
        {/* Buttons — outside the paper, hidden when printing                */}
        {/* ---------------------------------------------------------------- */}
        <div
          className="mt-4 flex w-full flex-col gap-3 print:hidden"
          style={{ maxWidth: 320 }}
        >
          <div className="flex justify-center gap-3">
            <Button
              variant="outline"
              className="flex-1 border-white/20 bg-white/10 text-white hover:bg-white/20"
              onClick={handlePrint}
              disabled={deviceBusy}
            >
              <Printer className="size-4" />
              {deviceBusy
                ? t('pos.receipt.printing')
                : printer.connected
                  ? t('pos.receipt.printDevice')
                  : t('pos.receipt.print')}
            </Button>
            <Button className="flex-1" onClick={onAction}>
              {actionLabel}
            </Button>
          </div>
          {/* Secondary action — full-width, visually separated below. Default tone is destructive
              (manager-gated Return sale, ADR 0061): red-toned so it never reads as a routine tap on
              this dark backdrop. 'neutral' is a plain outline for a non-destructive drill-in. */}
          {secondaryAction ? (
            <Button
              variant="outline"
              className={
                secondaryAction.tone === 'neutral'
                  ? 'w-full border-white/25 bg-white/5 text-white hover:bg-white/10'
                  : 'w-full border-red-400/40 bg-red-500/10 text-red-50 hover:bg-red-500/20 focus-visible:outline-red-300'
              }
              onClick={secondaryAction.onClick}
              disabled={secondaryAction.disabled}
            >
              {secondaryAction.label}
            </Button>
          ) : null}
        </div>
      </div>

      {/* ------------------------------------------------------------------ */}
      {/* Print-only paper — the same receipt paper is visible when printing  */}
      {/* because #native-thermal-receipt-paper is unhidden by the @media     */}
      {/* print rule above. The backdrop div is print:hidden via Tailwind.    */}
      {/* ------------------------------------------------------------------ */}
    </>
  )
}
