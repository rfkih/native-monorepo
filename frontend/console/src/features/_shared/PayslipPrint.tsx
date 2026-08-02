/**
 * PayslipPrint — the printable payslip document (Track P phase P9, the real Odoo-parity item per
 * the "Statutory outputs" plan note: Odoo l10n_id ships a payslip PDF, so this is the ONE statutory
 * output that must genuinely match, not just exceed, parity).
 *
 * Print isolation follows the SelfOrderQr precedent (not ThermalReceipt/KotView's hidden-duplicate-
 * layout trick): the payslip document renders ONCE, visible on screen inside the modal AND as the
 * print target — #native-payslip-print is registered in the global index.css print-isolation list
 * (mirroring #native-thermal-receipt-paper/#native-kot-print/#native-selforder-qr-print), so
 * printing hides every other page element and shows only this document on plain A4/Letter paper
 * (@page margin, no fixed 80mm width — a payslip is a normal document, not a thermal ticket).
 *
 * Money via formatMoney (Intl); dates via Intl.DateTimeFormat; every label an i18n key (rule 9).
 * Component labels resolve through `payslip.components.<KEY>` with a raw-key fallback (an unknown/
 * future component key never renders blank) — the SAME i18n namespace PayrollTab and Me use for
 * their own line tables, so a key never has two different displayed labels in the app.
 */
import { useTranslation } from 'react-i18next'
import { Printer, X } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { formatMoney } from '@/lib/money'

export interface PayslipPrintLine {
  componentKey: string
  /** 'EARNING' | 'DEDUCTION' */
  kind: string
  /** 'EMPLOYEE' | 'EMPLOYER' */
  bearer: string
  amountMinor: number
  currency: string
  illustrative: boolean
  ruleVersion: string | null
}

export interface PayslipPrintProps {
  companyName: string
  employeeName: string
  /** "YYYY-MM" */
  period: string
  runSeq: number
  /** 'REGULAR' | 'THR' */
  runType: string
  currency: string
  grossMinor: number
  deductionMinor: number
  netMinor: number
  illustrative: boolean
  lines: PayslipPrintLine[]
  locale: string
  onClose: () => void
}

function componentLabel(t: ReturnType<typeof useTranslation>['t'], componentKey: string): string {
  return t(`payslip.components.${componentKey}` as Parameters<typeof t>[0], {
    defaultValue: componentKey,
  })
}

export function PayslipPrint({
  companyName,
  employeeName,
  period,
  runSeq,
  runType,
  currency,
  grossMinor,
  deductionMinor,
  netMinor,
  illustrative,
  lines,
  locale,
  onClose,
}: PayslipPrintProps) {
  const { t } = useTranslation()

  const periodLabel = new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'long' }).format(
    new Date(`${period}-01T00:00:00`),
  )
  const generatedAtLabel = new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date())

  const earnings = lines.filter((l) => l.kind === 'EARNING')
  const deductions = lines.filter((l) => l.kind === 'DEDUCTION')
  const ruleVersions = [...new Set(lines.map((l) => l.ruleVersion).filter((v): v is string => !!v))]

  return (
    <div
      // Deliberately NO print:hidden here (review finding C1): this div is an ANCESTOR of the print
      // target #native-payslip-print below. @media print's display:none on an ancestor removes the
      // whole subtree from the print box tree — a descendant's visibility:visible (the global
      // index.css print-isolation rule) can NEVER undo that (see index.css's own comment on exactly
      // this hazard). Isolation instead relies purely on the global visibility-based rule, the same
      // as ThermalReceipt/SelfOrderQr (whose print targets also carry no print:hidden ancestor);
      // only the header CONTROLS below (a sibling of the print target, not an ancestor) are
      // print:hidden, mirroring KotView's modal-chrome-as-sibling shape.
      className="fixed inset-0 z-[70] grid place-items-start justify-center overflow-y-auto bg-black/60 p-4 py-8 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('payslip.print.title')}
    >
      <Card className="reveal flex w-full max-w-2xl flex-col overflow-hidden">
        {/* Header — screen only */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4 print:hidden">
          <h2 className="font-display text-lg font-semibold text-ink">{t('payslip.print.title')}</h2>
          <div className="flex items-center gap-2">
            <Button type="button" variant="outline" onClick={() => window.print()}>
              <Printer className="size-4" />
              {t('payslip.print.cta')}
            </Button>
            <button
              type="button"
              onClick={onClose}
              aria-label={t('common.close')}
              className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
            >
              <X className="size-4" />
            </button>
          </div>
        </div>

        {/* The document itself — visible on screen AND the print target. */}
        <div id="native-payslip-print" className="max-h-[70vh] overflow-y-auto px-8 py-6 print:max-h-none print:overflow-visible">
          <style>{`
            @media print {
              @page { margin: 14mm; }
              #native-payslip-print, #native-payslip-print * {
                color: #000 !important;
                background: #fff !important;
              }
            }
          `}</style>

          {/* Company + document header */}
          <div className="flex items-start justify-between border-b border-ink-200 pb-4">
            <div>
              <div className="text-lg font-bold text-ink">{companyName}</div>
              <div className="mt-1 text-sm text-ink-2">{employeeName}</div>
            </div>
            <div className="text-right">
              <div className="text-sm font-semibold uppercase tracking-wider text-ink-3">
                {t('payslip.print.title')}
              </div>
              <div className="mt-1 text-sm text-ink">
                {periodLabel}
                {runSeq > 1 ? ` · ${t('payslip.print.runLabel', { seq: runSeq })}` : ''}
              </div>
            </div>
          </div>

          {/* Badges: run type + provenance */}
          <div className="mt-3 flex flex-wrap gap-2">
            <Badge tone={runType === 'THR' ? 'info' : 'neutral'}>
              {t(`payslip.print.runType.${runType}` as Parameters<typeof t>[0], {
                defaultValue: runType,
              })}
            </Badge>
            <Badge tone={illustrative ? 'amber' : 'emerald'}>
              {illustrative
                ? t('payslip.print.provenanceIllustrative')
                : t('payslip.print.provenanceOfficial')}
            </Badge>
          </div>

          {lines.length === 0 ? (
            <p className="mt-6 text-sm text-ink-3">{t('payslip.print.noLines')}</p>
          ) : (
            <>
              {/* Earnings */}
              <div className="mt-5">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
                  {t('payslip.print.earnings')}
                </p>
                <div className="mt-1.5 divide-y divide-line">
                  {earnings.map((line, i) => (
                    <PayslipLineRow key={`${line.componentKey}-${i}`} line={line} locale={locale} t={t} />
                  ))}
                </div>
              </div>

              {/* Deductions */}
              <div className="mt-4">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
                  {t('payslip.print.deductions')}
                </p>
                <div className="mt-1.5 divide-y divide-line">
                  {deductions.map((line, i) => (
                    <PayslipLineRow key={`${line.componentKey}-${i}`} line={line} locale={locale} t={t} />
                  ))}
                </div>
              </div>
            </>
          )}

          {/* Totals */}
          <div className="mt-5 border-t border-ink-200 pt-3">
            <TotalRow label={t('payslip.print.gross')} minor={grossMinor} currency={currency} locale={locale} />
            <TotalRow
              label={t('payslip.print.totalDeductions')}
              minor={deductionMinor}
              currency={currency}
              locale={locale}
            />
            <TotalRow
              label={t('payslip.print.net')}
              minor={netMinor}
              currency={currency}
              locale={locale}
              emphatic
            />
          </div>

          {/* Provenance footer */}
          <div className="mt-4 border-t border-ink-200 pt-3 text-[11px] text-ink-3">
            {ruleVersions.length > 0 ? (
              <div>
                {t('payslip.print.ruleVersions')}: {ruleVersions.join(', ')}
              </div>
            ) : null}
            <div className="mt-1">{t('payslip.print.generatedAt', { date: generatedAtLabel })}</div>
          </div>
        </div>
      </Card>
    </div>
  )
}

function PayslipLineRow({
  line,
  locale,
  t,
}: {
  line: PayslipPrintLine
  locale: string
  t: ReturnType<typeof useTranslation>['t']
}) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5 text-sm">
      <span className="text-ink-2">
        {componentLabel(t, line.componentKey)}
        {line.bearer === 'EMPLOYER' ? (
          <span className="ml-1.5 text-xs text-ink-3">({t('payslip.print.employerBorne')})</span>
        ) : null}
        {line.illustrative ? (
          <span className="ml-1.5">
            <Badge tone="amber">{t('payslip.print.provenanceIllustrative')}</Badge>
          </span>
        ) : null}
      </span>
      <span className="tnum font-mono text-sm font-medium text-ink">
        {formatMoney(line.amountMinor, line.currency, locale)}
      </span>
    </div>
  )
}

function TotalRow({
  label,
  minor,
  currency,
  locale,
  emphatic,
}: {
  label: string
  minor: number
  currency: string
  locale: string
  emphatic?: boolean
}) {
  return (
    <div className="flex items-center justify-between py-1">
      <span className={emphatic ? 'text-sm font-semibold text-ink' : 'text-sm text-ink-2'}>
        {label}
      </span>
      <span
        className={
          emphatic
            ? 'tnum font-mono text-base font-bold text-ink'
            : 'tnum font-mono text-sm font-medium text-ink'
        }
      >
        {formatMoney(minor, currency, locale)}
      </span>
    </div>
  )
}
