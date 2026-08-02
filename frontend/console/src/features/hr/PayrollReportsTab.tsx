/**
 * PayrollReportsTab — the statutory CSV exports (Track P phase P9): 1721-A1 (annual, owner-only),
 * SPT Masa PPh 21 monthly summary (aggregate, owner/manager), and the BPJS contribution summary
 * (owner-only — see PayrollReportReader's class Javadoc for the judgment call). Every export ships
 * as an ILLUSTRATIVE LAYOUT — these CSVs EXCEED Odoo's parity bar (Odoo l10n_id ships none of this),
 * but the column shape is not the certified DJP/BPJS schema, so each card carries that caveat.
 *
 * Owner-only reports stay visible to a manager (so they understand the report exists) but the
 * download button is disabled with an explanatory note — the SAME posture as the bank-file
 * download in PayrollTab's RunDetail.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Download, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { PeriodNav } from '@/features/_shared/financeUi'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import {
  downloadBpjsSummaryReport,
  downloadBukti1721A1Report,
  downloadPph21MonthlyReport,
} from './api'

type ReportKind = '1721a1' | 'pph21-monthly' | 'bpjs-summary'

export function PayrollReportsTab({
  companyId,
  actor,
  locale,
  initialPeriod,
}: {
  companyId: string
  actor: string
  locale: string
  /** Track P Phase P10 — seeds the period/year nav from a run-detail "View reports" shortcut
   * (PayrollTab); read only once, on mount — the nav below still moves independently after that. */
  initialPeriod?: string
}) {
  const { t } = useTranslation()
  const auth = useAuth()
  const isOwner = hasAnyRole(auth.roles, 'owner')

  const [year, setYear] = useState(initialPeriod?.slice(0, 4) ?? String(new Date().getFullYear()))
  const [period, setPeriod] = useState(initialPeriod ?? currentPeriod())
  const [downloading, setDownloading] = useState<ReportKind | null>(null)
  const [errorKind, setErrorKind] = useState<ReportKind | null>(null)

  async function handleDownload(kind: ReportKind, run: () => Promise<void>) {
    setErrorKind(null)
    setDownloading(kind)
    try {
      await run()
    } catch {
      setErrorKind(kind)
    } finally {
      setDownloading(null)
    }
  }

  const yearValid = /^\d{4}$/.test(year)

  return (
    <div className="flex flex-col gap-5">
      <p className="text-sm text-ink-3">{t('hr.payroll.reports.subtitle')}</p>

      {/* 1721-A1 — annual, owner-only, real NIK/NPWP */}
      <Card className="p-5">
        <p className="text-[15px] font-semibold text-ink">{t('hr.payroll.reports.bukti1721a1.title')}</p>
        <p className="mt-1 text-sm leading-relaxed text-ink-3">
          {t('hr.payroll.reports.bukti1721a1.body')}
        </p>
        <p className="mt-1.5 text-xs text-amber">{t('hr.payroll.reports.illustrativeLayoutNote')}</p>
        {!isOwner ? (
          <p className="mt-1.5 flex items-center gap-1.5 text-xs text-ink-3">
            <TriangleAlert className="size-3.5 shrink-0" aria-hidden="true" />
            {t('hr.payroll.reports.ownerOnly')}
          </p>
        ) : null}
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <Field label={t('hr.payroll.reports.yearLabel')}>
            <TextInput
              value={year}
              onChange={(e) => setYear(e.target.value.replace(/[^0-9]/g, '').slice(0, 4))}
              className="w-28"
              inputMode="numeric"
              maxLength={4}
            />
          </Field>
          <Button
            type="button"
            variant="outline"
            disabled={!isOwner || !yearValid || downloading === '1721a1'}
            onClick={() =>
              handleDownload('1721a1', () => downloadBukti1721A1Report({ companyId, actor, year }))
            }
          >
            <Download className="size-4" />
            {downloading === '1721a1'
              ? t('hr.payroll.reports.downloading')
              : t('hr.payroll.reports.download')}
          </Button>
        </div>
        {errorKind === '1721a1' ? (
          <p className="mt-2 text-xs text-loss">{t('hr.payroll.reports.error')}</p>
        ) : null}
      </Card>

      {/* pph21-monthly — aggregate, owner/manager */}
      <Card className="p-5">
        <p className="text-[15px] font-semibold text-ink">{t('hr.payroll.reports.pph21Monthly.title')}</p>
        <p className="mt-1 text-sm leading-relaxed text-ink-3">
          {t('hr.payroll.reports.pph21Monthly.body')}
        </p>
        <p className="mt-1.5 text-xs text-amber">{t('hr.payroll.reports.illustrativeLayoutNote')}</p>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <PeriodNav
            period={period}
            locale={locale}
            onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
            onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
            prevLabel={t('orgHub.overview.prevMonth')}
            nextLabel={t('orgHub.overview.nextMonth')}
          />
          <Button
            type="button"
            variant="outline"
            disabled={downloading === 'pph21-monthly'}
            onClick={() =>
              handleDownload('pph21-monthly', () =>
                downloadPph21MonthlyReport({ companyId, actor, period }),
              )
            }
          >
            <Download className="size-4" />
            {downloading === 'pph21-monthly'
              ? t('hr.payroll.reports.downloading')
              : t('hr.payroll.reports.download')}
          </Button>
        </div>
        {errorKind === 'pph21-monthly' ? (
          <p className="mt-2 text-xs text-loss">{t('hr.payroll.reports.error')}</p>
        ) : null}
      </Card>

      {/* bpjs-summary — per-employee wage, owner-only */}
      <Card className="p-5">
        <p className="text-[15px] font-semibold text-ink">{t('hr.payroll.reports.bpjsSummary.title')}</p>
        <p className="mt-1 text-sm leading-relaxed text-ink-3">
          {t('hr.payroll.reports.bpjsSummary.body')}
        </p>
        <p className="mt-1.5 text-xs text-amber">{t('hr.payroll.reports.illustrativeLayoutNote')}</p>
        {!isOwner ? (
          <p className="mt-1.5 flex items-center gap-1.5 text-xs text-ink-3">
            <TriangleAlert className="size-3.5 shrink-0" aria-hidden="true" />
            {t('hr.payroll.reports.ownerOnly')}
          </p>
        ) : null}
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <PeriodNav
            period={period}
            locale={locale}
            onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
            onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
            prevLabel={t('orgHub.overview.prevMonth')}
            nextLabel={t('orgHub.overview.nextMonth')}
          />
          <Button
            type="button"
            variant="outline"
            disabled={!isOwner || downloading === 'bpjs-summary'}
            onClick={() =>
              handleDownload('bpjs-summary', () =>
                downloadBpjsSummaryReport({ companyId, actor, period }),
              )
            }
          >
            <Download className="size-4" />
            {downloading === 'bpjs-summary'
              ? t('hr.payroll.reports.downloading')
              : t('hr.payroll.reports.download')}
          </Button>
        </div>
        {errorKind === 'bpjs-summary' ? (
          <p className="mt-2 text-xs text-loss">{t('hr.payroll.reports.error')}</p>
        ) : null}
      </Card>
    </div>
  )
}
