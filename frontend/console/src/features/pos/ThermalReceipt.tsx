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

import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { Printer } from 'lucide-react'
import { Button } from '@/components/ui/Button'

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
}

// ---------------------------------------------------------------------------
// Decorative barcode — pure CSS/divs, no external lib
// ---------------------------------------------------------------------------

/**
 * Renders a row of vertical black bars of varying widths to simulate a barcode.
 * Uses a seeded-ish pattern derived from the reference string so it is stable
 * across renders for the same reference. Not a scannable barcode — decorative only.
 */
function DecorativeBarcode({ reference }: { reference: string }) {
  // Derive a deterministic sequence of bar widths (1–3 px) from the reference chars.
  const bars: number[] = []
  const totalBars = 60
  for (let i = 0; i < totalBars; i++) {
    const charCode = reference.charCodeAt(i % reference.length) || 65
    bars.push(((charCode + i * 7) % 3) + 1)
  }

  return (
    <div
      aria-hidden="true"
      style={{
        display: 'flex',
        alignItems: 'stretch',
        height: 40,
        gap: 1.5,
        justifyContent: 'center',
        margin: '8px 0 4px',
      }}
    >
      {bars.map((width, idx) => (
        <div
          key={idx}
          style={{
            width,
            backgroundColor: '#000',
            flexShrink: 0,
          }}
        />
      ))}
    </div>
  )
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
  isPending,
  pendingNote,
  isProvisional,
  provisionalNote,
}: ThermalProps) {
  const { t } = useTranslation()
  const headingId = useId()

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
        className="fixed inset-0 z-40 grid place-items-start justify-center overflow-y-auto bg-black/60 py-8 px-4 backdrop-blur-sm"
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
              <DecorativeBarcode reference={reference} />
              <div style={{ fontSize: 10, color: '#666', letterSpacing: '0.05em' }}>
                {reference}
              </div>
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
        {/* Buttons — outside the paper, hidden when printing                */}
        {/* ---------------------------------------------------------------- */}
        <div
          className="mt-4 flex w-full justify-center gap-3 print:hidden"
          style={{ maxWidth: 320 }}
        >
          <Button
            variant="outline"
            className="flex-1 border-white/20 bg-white/10 text-white hover:bg-white/20"
            onClick={onPrint}
          >
            <Printer className="size-4" />
            {t('pos.receipt.print')}
          </Button>
          <Button className="flex-1" onClick={onAction}>
            {actionLabel}
          </Button>
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
