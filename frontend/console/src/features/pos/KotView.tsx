/**
 * KotView — Kitchen Order Ticket (KOT) print layout.
 *
 * Items + modifiers + qty + table + guest label + time. NO prices (kitchen
 * does not need money figures).
 *
 * Print isolation: triggers window.print() targeting #native-kot-print.
 * The customer receipt uses #native-thermal-receipt-paper — the two ids never collide.
 * The @media print rule hides every other element so only the KOT prints.
 *
 * Strings rule (rule 9): all user-facing text is an i18n key. No money shown.
 */
import { useTranslation } from 'react-i18next'
import { X, ChefHat } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { printCurrentPage } from '@/lib/nativeShell'
import type { BillLineResponse, BillResponse } from './billsApi'

interface Props {
  bill: BillResponse
  /** Lines to include in the KOT — typically all UNPAID lines on the bill. */
  lines: BillLineResponse[]
  locale: string
  tableLabel?: string | null
  onClose: () => void
}

export function KotView({ bill, lines, locale, tableLabel, onClose }: Props) {
  const { t } = useTranslation()
  useBackDismiss(onClose)
  useScrollLock()

  function handlePrint() {
    printCurrentPage('kot')
  }

  return (
    <>
      {/* Print-only KOT — hidden on screen, shown when window.print() fires. */}
      <KotPrintLayout bill={bill} lines={lines} locale={locale} tableLabel={tableLabel} t={t} />

      <div
        className="fixed inset-0 z-[70] grid place-items-center bg-black/40 p-4 backdrop-blur-sm print:hidden"
        role="dialog"
        aria-modal="true"
        aria-label={t('kot.title')}
      >
        <Card className="reveal flex max-h-full w-full max-w-sm flex-col overflow-hidden">
          {/* Header */}
          <div className="flex items-center justify-between border-b border-line px-5 py-4">
            <div className="flex items-center gap-2">
              <ChefHat className="size-5 text-ink-2" aria-hidden="true" />
              <h2 className="font-display text-lg font-semibold text-ink">{t('kot.title')}</h2>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label={t('common.close')}
              className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <X className="size-4" />
            </button>
          </div>

          {/* Meta */}
          <div className="border-b border-line px-5 py-3 text-sm">
            <div className="flex items-baseline justify-between">
              <span className="text-ink-3">{t('kot.guest')}</span>
              <span className="font-medium text-ink">{bill.guestLabel}</span>
            </div>
            {tableLabel ? (
              <div className="mt-1 flex items-baseline justify-between">
                <span className="text-ink-3">{t('pos.table.label')}</span>
                <span className="font-medium text-ink">{tableLabel}</span>
              </div>
            ) : null}
            <div className="mt-1 flex items-baseline justify-between">
              <span className="text-ink-3">{t('kot.time')}</span>
              <span className="font-mono text-ink">
                {new Intl.DateTimeFormat(locale, { timeStyle: 'short' }).format(new Date())}
              </span>
            </div>
          </div>

          {/* Item list */}
          <div className="min-h-0 max-h-[50vh] overflow-y-auto">
            {lines.length === 0 ? (
              <p className="px-5 py-8 text-center text-sm text-ink-3">{t('kot.noItems')}</p>
            ) : (
              <ul className="divide-y divide-line">
                {lines.map((line) => (
                  <li key={line.id} className="px-5 py-3">
                    <div className="flex items-center gap-3">
                      {/* Checkbox-style qty badge */}
                      <span className="tnum grid size-8 shrink-0 place-items-center rounded-lg border-2 border-ink-200 font-mono text-sm font-bold text-ink">
                        {line.qty}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="font-semibold text-ink">{line.nameSnapshot}</div>
                        {line.modifiers.length > 0 ? (
                          <div className="mt-0.5 flex flex-wrap gap-x-2 text-xs text-ink-3">
                            {line.modifiers.map((mod) => (
                              <span key={mod.optionId}>{mod.nameSnapshot}</span>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* CTA */}
          <div className="border-t border-line px-5 py-4 flex gap-2">
            <Button variant="outline" className="flex-1" onClick={handlePrint} disabled={lines.length === 0}>
              <ChefHat className="size-4" />
              {t('kot.print')}
            </Button>
            <Button className="flex-1" onClick={onClose}>
              <X className="size-4" />
              {t('common.close')}
            </Button>
          </div>
        </Card>
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// KotPrintLayout — hidden on screen; only visible during window.print()
// ---------------------------------------------------------------------------

interface PrintProps {
  bill: BillResponse
  lines: BillLineResponse[]
  locale: string
  tableLabel?: string | null
  t: (key: string, opts?: Record<string, unknown>) => string
}

function KotPrintLayout({ bill, lines, locale, tableLabel, t }: PrintProps) {
  const printTime = new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date())

  return (
    <>
      <style>{`
        @media print {
          /* Print isolation is handled globally in index.css (visibility-based). */
          @page { margin: 6mm; size: 80mm auto; }
        }
        #native-kot-print {
          display: none;
          font-family: 'Courier New', Courier, monospace;
          font-size: 13px;
          color: #000;
          background: #fff;
          max-width: 300px;
          margin: 0 auto;
        }
        #native-kot-print .kot-center { text-align: center; }
        #native-kot-print .kot-bold { font-weight: bold; }
        #native-kot-print .kot-rule { border: none; border-top: 2px solid #000; margin: 6px 0; }
        #native-kot-print .kot-rule-dashed { border: none; border-top: 1px dashed #000; margin: 4px 0; }
        #native-kot-print .kot-item { margin: 8px 0; }
        #native-kot-print .kot-item-row { display: flex; gap: 8px; align-items: flex-start; font-size: 15px; font-weight: bold; }
        #native-kot-print .kot-qty { min-width: 28px; border: 2px solid #000; text-align: center; padding: 1px 4px; }
        #native-kot-print .kot-modifier { padding-left: 36px; font-size: 11px; margin-top: 2px; }
        #native-kot-print .kot-meta { display: flex; justify-content: space-between; font-size: 11px; margin: 2px 0; }
      `}</style>

      <div id="native-kot-print" aria-hidden="true">
        {/* KOT header */}
        <div className="kot-center kot-bold" style={{ fontSize: 16, marginBottom: 4 }}>
          *** {t('kot.title').toUpperCase()} ***
        </div>

        <hr className="kot-rule" />

        {/* Meta */}
        <div className="kot-meta">
          <span>{t('kot.guest')}: {bill.guestLabel}</span>
        </div>
        {tableLabel ? (
          <div className="kot-meta">
            <span>{t('pos.table.label')}: {tableLabel}</span>
          </div>
        ) : null}
        <div className="kot-meta">
          <span>{t('kot.time')}: {printTime}</span>
        </div>

        <hr className="kot-rule" />

        {/* Items — big, legible, checkbox-per-item feel */}
        {lines.map((line, idx) => (
          <div key={line.id} className="kot-item">
            <div className="kot-item-row">
              <span className="kot-qty">{line.qty}</span>
              <span>{line.nameSnapshot}</span>
            </div>
            {line.modifiers.map((mod) => (
              <div key={mod.optionId} className="kot-modifier">
                + {mod.nameSnapshot}
              </div>
            ))}
            {idx < lines.length - 1 ? <hr className="kot-rule-dashed" style={{ marginTop: 8 }} /> : null}
          </div>
        ))}

        <hr className="kot-rule" />
        <div className="kot-center" style={{ fontSize: 10, marginTop: 4 }}>
          {t('kot.footer')}
        </div>
      </div>
    </>
  )
}
