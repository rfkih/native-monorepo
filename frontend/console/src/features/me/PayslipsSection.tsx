/**
 * PayslipsSection — the payslip list + YTD card + expandable run detail, extracted verbatim
 * from Me.tsx so the phone payslips screen (/me/payslips) and the desktop /me page render the
 * exact same components (pure code motion — the deduction sign-flip logic and its comment
 * travel untouched; never re-derive it).
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronRight, Printer, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { KpiTile } from '@/features/_shared/financeUi'
import { PayslipPrint } from '@/features/_shared/PayslipPrint'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/cn'
import { useSession } from '@/lib/session'
import {
  useMyPayslip,
  useMyPayslips,
  useMyPayslipsYtd,
  type MyPayslipHeader,
  type MyPayslipsYtdSummary,
} from './api'

/** A period ("YYYY-MM") is December — the run that may carry the annual Art-17 true-up. */
function isDecemberPeriod(period: string): boolean {
  return period.endsWith('-12')
}

export function PayslipsSection({
  companyId,
  actor,
  locale,
  employeeName,
}: {
  companyId: string
  actor: string
  locale: string
  employeeName: string
}) {
  const { t } = useTranslation()
  const payslips = useMyPayslips({ companyId, actor, enabled: true })
  const [openRunId, setOpenRunId] = useState<string | null>(null)
  // Track P Phase P10 — the YTD summary card, client-summed from the caller's own payslips (see
  // useMyPayslipsYtd's doc comment for the cache-sharing story; no new endpoint).
  const ytd = useMyPayslipsYtd({ companyId, actor, year: new Date().getFullYear(), enabled: true })

  return (
    <section>
      <h2 className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('me.payslips.title')}
      </h2>
      {payslips.isLoading ? (
        <Card className="mt-2 p-8 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : payslips.isError ? (
        <Card className="mt-2 p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('me.error')}
        </Card>
      ) : (payslips.data ?? []).length === 0 ? (
        <p className="mt-2 text-sm text-ink-3">{t('me.payslips.empty')}</p>
      ) : (
        <>
          <PayslipYtdCard ytd={ytd} locale={locale} />
          <Card className="mt-2 rounded-[20px] p-2.5">
            {(payslips.data ?? []).map((slip) => (
              <PayslipRow
                key={slip.runId}
                slip={slip}
                open={openRunId === slip.runId}
                onToggle={() =>
                  setOpenRunId((cur) => (cur === slip.runId ? null : slip.runId))
                }
                companyId={companyId}
                actor={actor}
                locale={locale}
                employeeName={employeeName}
              />
            ))}
          </Card>
        </>
      )}
    </section>
  )
}

/** Track P Phase P10 — gross + PPh 21 withheld year-to-date, above the run-by-run payslip list. */
function PayslipYtdCard({ ytd, locale }: { ytd: MyPayslipsYtdSummary; locale: string }) {
  const { t } = useTranslation()
  const { company } = useSession()
  // Every fetched run detail already carries its own currency; this only covers the sliver where
  // `loading` has settled false but every per-run detail request errored (currency never arrives).
  const fallbackCurrency = ytd.currency ?? company?.baseCurrency ?? 'IDR'
  return (
    <Card className="mt-2 p-4">
      <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('me.payslips.ytd.title', { year: ytd.year })}
      </p>
      {ytd.runCount === 0 ? (
        <p className="mt-2 text-sm text-ink-3">{t('me.payslips.ytd.empty')}</p>
      ) : (
        <>
          <div className="mt-2 grid gap-3 sm:grid-cols-2">
            <KpiTile
              label={t('me.payslips.ytd.gross')}
              minor={ytd.grossMinor}
              currency={fallbackCurrency}
              locale={locale}
              loading={ytd.loading}
            />
            <KpiTile
              label={t('me.payslips.ytd.pph21')}
              minor={ytd.pph21Minor}
              currency={fallbackCurrency}
              locale={locale}
              loading={ytd.loading}
            />
          </div>
          {ytd.isError ? <p className="mt-2 text-xs text-loss">{t('me.error')}</p> : null}
        </>
      )}
    </Card>
  )
}

function PayslipRow({
  slip,
  open,
  onToggle,
  companyId,
  actor,
  locale,
  employeeName,
}: {
  slip: MyPayslipHeader
  open: boolean
  onToggle: () => void
  companyId: string
  actor: string
  locale: string
  employeeName: string
}) {
  const { t } = useTranslation()
  const { company } = useSession()
  const [showPrint, setShowPrint] = useState(false)
  const detail = useMyPayslip({ companyId, actor, runId: open ? slip.runId : null, enabled: open })

  return (
    <div>
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left transition-colors hover:bg-hover"
      >
        {open ? (
          <ChevronDown className="size-4 text-ink-3" aria-hidden="true" />
        ) : (
          <ChevronRight className="size-4 text-ink-3" aria-hidden="true" />
        )}
        <span className="text-[15px] font-semibold text-ink">{slip.period}</span>
        {slip.runSeq > 1 ? (
          <span className="text-xs text-ink-3">{t('me.payslips.runSeq', { seq: slip.runSeq })}</span>
        ) : null}
        {isDecemberPeriod(slip.period) && !slip.illustrative ? (
          <Badge tone="info">{t('me.payslips.trueUp')}</Badge>
        ) : null}
        {slip.illustrative ? (
          <Badge tone="amber">{t('me.payslips.illustrative')}</Badge>
        ) : null}
        <span className="ml-auto text-xs text-ink-3">
          {t('me.payslips.lines', { count: slip.lineCount })}
        </span>
      </button>

      {open ? (
        <div className="px-3 pb-4 pt-1">
          {detail.isLoading ? (
            <Spinner className="my-3 text-brand-500" />
          ) : detail.isError || !detail.data ? (
            <p className="text-sm text-loss">{t('me.error')}</p>
          ) : (
            <>
              <div className="flex justify-end">
                <Button type="button" variant="outline" onClick={() => setShowPrint(true)}>
                  <Printer className="size-4" />
                  {t('payslip.print.cta')}
                </Button>
              </div>
              <div className="mt-3 grid gap-3 sm:grid-cols-3">
                <KpiTile
                  label={t('me.payslips.gross')}
                  minor={detail.data.grossMinor}
                  currency={detail.data.currency}
                  locale={locale}
                  loading={false}
                />
                <KpiTile
                  label={t('me.payslips.deductions')}
                  minor={detail.data.deductionMinor}
                  currency={detail.data.currency}
                  locale={locale}
                  loading={false}
                />
                <KpiTile
                  label={t('me.payslips.net')}
                  minor={detail.data.netMinor}
                  currency={detail.data.currency}
                  locale={locale}
                  loading={false}
                  emphatic
                />
              </div>
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="text-[11px] uppercase tracking-wider text-ink-3">
                      <th className="py-1.5 pr-4 font-semibold">{t('me.payslips.component')}</th>
                      <th className="py-1.5 pr-4 font-semibold">{t('me.payslips.kind')}</th>
                      <th className="py-1.5 text-right font-semibold">{t('me.payslips.amount')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.data.lines.map((line, i) => {
                      // A DEDUCTION is conventionally shown NEGATED (it subtracts from net pay);
                      // stored deduction amounts are ordinarily positive, so this flips the sign
                      // for display. The December/final-month Art-17 true-up (Track P phase P3)
                      // can produce a NEGATIVE stored PPh21 line (an over-withheld refund) —
                      // flipping ITS sign correctly renders a POSITIVE credit, not a double
                      // minus. Never concatenate a literal minus glyph in front of
                      // Intl.NumberFormat's own sign (formatMoney already renders one for a
                      // negative value) — that was the bug: "−" + "-Rp150.000" rendered as a
                      // double negative.
                      const displayMinor = line.kind === 'DEDUCTION' ? -line.amountMinor : line.amountMinor
                      // > 0, not >= 0: a zero PPh21 line (no liability, nothing withheld either)
                      // is not a refund — only a STRICTLY positive displayed deduction (the
                      // stored amount was negative) is an actual credit back to the employee.
                      const isCredit = line.kind === 'DEDUCTION' && displayMinor > 0
                      return (
                        <tr key={`${line.componentKey}-${i}`} className="text-ink-2">
                          <td className="py-1.5 pr-4 text-xs">
                            {t(`payslip.components.${line.componentKey}` as Parameters<typeof t>[0], {
                              defaultValue: line.componentKey,
                            })}
                          </td>
                          <td className="py-1.5 pr-4 text-xs">
                            {t(
                              line.kind === 'EARNING'
                                ? 'me.payslips.earning'
                                : 'me.payslips.deduction',
                            )}
                            {line.bearer === 'EMPLOYER' ? (
                              <span className="ml-1 text-ink-3">
                                ({t('me.payslips.employer')})
                              </span>
                            ) : null}
                            {isCredit ? (
                              <span className="ml-1.5">
                                <Badge tone="profit">{t('me.payslips.trueUpCredit')}</Badge>
                              </span>
                            ) : null}
                          </td>
                          <td
                            className={cn(
                              'tnum py-1.5 text-right font-mono text-xs',
                              line.kind !== 'DEDUCTION'
                                ? 'text-ink'
                                : isCredit
                                  ? 'text-profit-ink'
                                  : 'text-loss',
                            )}
                          >
                            {formatMoney(displayMinor, line.currency, locale)}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      ) : null}

      {showPrint && detail.data ? (
        <PayslipPrint
          companyName={company?.name ?? ''}
          employeeName={employeeName}
          period={detail.data.period}
          runSeq={detail.data.runSeq}
          runType={detail.data.runType}
          currency={detail.data.currency}
          grossMinor={detail.data.grossMinor}
          deductionMinor={detail.data.deductionMinor}
          netMinor={detail.data.netMinor}
          illustrative={detail.data.illustrative}
          lines={detail.data.lines}
          locale={locale}
          onClose={() => setShowPrint(false)}
        />
      ) : null}
    </div>
  )
}
