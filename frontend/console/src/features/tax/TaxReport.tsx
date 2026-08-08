import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Download, FileCheck2, Info, ReceiptText, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Skeleton, StatCardsSkeleton } from '@/components/ui/Skeleton'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState, KpiTile } from '@/features/_shared/financeUi'
import { downloadCsv } from '@/lib/csv'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, minorToMajor } from '@/lib/money'
import { formatPeriod } from '@/lib/period'
import {
  fetchEfaktur,
  useFileReturn,
  useSettleReturn,
  useTaxFilings,
  useVatReturn,
  type NetDirection,
  type TaxFiling,
  type TaxFilingStatus,
} from './api'
import { taxErrorKey } from './format'

function currentMonth(): string {
  return new Date().toISOString().slice(0, 7)
}

function directionTone(direction: NetDirection): 'info' | 'profit' {
  return direction === 'PAYABLE' ? 'info' : 'profit'
}

function statusTone(status: TaxFilingStatus): 'info' | 'profit' {
  return status === 'SETTLED' ? 'profit' : 'info'
}

/**
 * Tax / PPN return — the GL-derived VAT report for a month (output VAT − input VAT = net
 * payable/creditable), with File (posts the period-end netting entry + seals the period), Settle
 * (pays a net-payable return), an e-Faktur CSV export, and the filing history. Owner/manager only.
 * ILLUSTRATIVE / SME-gated (rate, carryforward policy, e-Faktur layout) — see ADR 0017.
 */
export function TaxReport() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)
  const [period, setPeriod] = useState(currentMonth())
  const [actionError, setActionError] = useState<string | null>(null)

  const companyId = company?.companyId ?? ''
  const actor = company?.actor ?? ''
  const enabled = !!company

  const returnQuery = useVatReturn({ companyId, actor, period, enabled })
  const filingsQuery = useTaxFilings({ companyId, actor, enabled })
  const fileReturn = useFileReturn({ companyId, actor })
  const settleReturn = useSettleReturn({ companyId, actor })

  if (!company) {
    return <EmptyState title={t('tax.report.noCompany')} hint={t('tax.report.noCompanyHint')} />
  }

  const data = returnQuery.data ?? null
  const currency = data?.currency ?? company.baseCurrency
  const filings = filingsQuery.data ?? []

  const hasActivity = !!data && (data.outputVatMinor > 0 || data.inputVatMinor > 0)
  const canFile = !!data && !data.filed && hasActivity
  const canSettle =
    !!data &&
    data.filed &&
    data.filingStatus === 'FILED' &&
    data.netDirection === 'PAYABLE' &&
    data.netMinor > 0 &&
    !!data.filingId

  async function onFile() {
    setActionError(null)
    try {
      await fileReturn.mutateAsync(period)
    } catch (e) {
      setActionError(t(taxErrorKey(e)))
    }
  }

  async function onSettle() {
    if (!data?.filingId) return
    setActionError(null)
    try {
      await settleReturn.mutateAsync(data.filingId)
    } catch (e) {
      setActionError(t(taxErrorKey(e)))
    }
  }

  async function onExportEfaktur() {
    const exp = await fetchEfaktur({ companyId, actor, period })
    if (!exp) return
    downloadCsv(`efaktur-${period}.csv`, [
      [t('tax.efaktur.title'), period],
      [
        t('tax.efaktur.colInvoice'),
        t('tax.efaktur.colDate'),
        t('tax.efaktur.colCustomer'),
        t('tax.efaktur.colTaxId'),
        t('tax.efaktur.colDpp'),
        t('tax.efaktur.colPpn'),
        t('tax.efaktur.colTotal'),
        t('tax.efaktur.colCurrency'),
      ],
      ...exp.lines.map((l) => [
        l.invoiceNumber,
        l.issueDate,
        l.customerName,
        l.customerTaxId ?? '',
        minorToMajor(l.dppMinor, l.currency),
        minorToMajor(l.ppnMinor, l.currency),
        minorToMajor(l.totalMinor, l.currency),
        l.currency,
      ]),
    ])
  }

  const netLabel =
    data?.netDirection === 'CREDITABLE' ? t('tax.report.netCreditable') : t('tax.report.netPayable')

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-[28px] font-bold tracking-[-0.02em] text-ink">
            {t('tax.report.title')}
          </h1>
          <p className="mt-1.5 text-sm text-ink-3">{t('tax.report.subtitle')}</p>
        </div>
        <div className="flex flex-wrap items-end gap-2.5">
          <Field label={t('tax.report.periodLabel')} htmlFor="tax-period">
            <TextInput
              id="tax-period"
              type="month"
              value={period}
              onChange={(e) => setPeriod(e.target.value)}
              className="h-11"
            />
          </Field>
          <Button type="button" variant="outline" onClick={onExportEfaktur}>
            <Download className="size-[15px]" aria-hidden />
            {t('tax.report.exportEfaktur')}
          </Button>
        </div>
      </div>

      <Card className="flex items-start gap-2.5 border-info/30 bg-tint-info/40 p-3.5 text-sm text-ink-2">
        <Info className="mt-0.5 size-[15px] shrink-0 text-info" aria-hidden />
        <span>{t('tax.report.illustrativeNote')}</span>
      </Card>

      {returnQuery.isError ? (
        <Card className="p-8 text-center text-sm text-loss">
          <TriangleAlert className="mx-auto mb-2 size-5" />
          {t('tax.report.error')}
        </Card>
      ) : returnQuery.isLoading || !data ? (
        <>
          <StatCardsSkeleton cards={3} />
          <Skeleton className="h-16 rounded-card" />
        </>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-3">
            <KpiTile
              label={t('tax.report.outputVat')}
              minor={data.outputVatMinor}
              currency={currency}
              locale={locale}
              loading={false}
            />
            <KpiTile
              label={t('tax.report.inputVat')}
              minor={data.inputVatMinor}
              currency={currency}
              locale={locale}
              loading={false}
            />
            <KpiTile
              label={netLabel}
              minor={data.netMinor}
              currency={currency}
              locale={locale}
              loading={false}
              emphatic
              tone={data.netDirection === 'CREDITABLE' ? 'text-profit' : 'text-ink'}
            />
          </div>

          <Card className="flex flex-wrap items-center justify-between gap-3 p-4">
            <div className="flex flex-wrap items-center gap-2 text-sm text-ink-2">
              {data.filed ? (
                <>
                  <Badge tone={statusTone(data.filingStatus ?? 'FILED')}>
                    {t(`tax.status.${data.filingStatus ?? 'FILED'}` as Parameters<typeof t>[0])}
                  </Badge>
                  <Badge tone={directionTone(data.netDirection)}>
                    {t(`tax.direction.${data.netDirection}` as Parameters<typeof t>[0])}
                  </Badge>
                  <span>{t('tax.report.filedFor', { period: formatPeriod(period, locale) })}</span>
                </>
              ) : hasActivity ? (
                <span>{t('tax.report.unfiledHint', { period: formatPeriod(period, locale) })}</span>
              ) : (
                <span>{t('tax.report.noActivityHint')}</span>
              )}
            </div>
            <div className="flex items-center gap-2.5">
              {!data.filed ? (
                <Button type="button" onClick={onFile} disabled={!canFile || fileReturn.isPending}>
                  <FileCheck2 className="size-[15px]" aria-hidden />
                  {t('tax.report.fileReturn')}
                </Button>
              ) : canSettle ? (
                <Button type="button" onClick={onSettle} disabled={settleReturn.isPending}>
                  <ReceiptText className="size-[15px]" aria-hidden />
                  {t('tax.report.settle')}
                </Button>
              ) : null}
            </div>
          </Card>

          {actionError ? (
            <Card className="flex items-center gap-2 p-3.5 text-sm text-loss">
              <TriangleAlert className="size-[15px] shrink-0" aria-hidden />
              {actionError}
            </Card>
          ) : null}
        </>
      )}

      <div>
        <h2 className="mb-2.5 mt-2 font-display text-lg font-semibold text-ink">
          {t('tax.history.title')}
        </h2>
        {filings.length === 0 ? (
          <EmptyState title={t('tax.history.empty')} hint={t('tax.history.emptyHint')} />
        ) : (
          <Card className="overflow-x-auto rounded-[20px]">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line bg-paper text-left text-[11px] font-bold uppercase tracking-[0.08em] text-ink-3">
                  <th className="px-4 py-3">{t('tax.history.colPeriod')}</th>
                  <th className="px-4 py-3">{t('tax.history.colStatus')}</th>
                  <th className="px-4 py-3 text-right">{t('tax.report.outputVat')}</th>
                  <th className="px-4 py-3 text-right">{t('tax.report.inputVat')}</th>
                  <th className="px-4 py-3 text-right">{t('tax.history.colNet')}</th>
                </tr>
              </thead>
              <tbody>
                {filings.map((f: TaxFiling) => (
                  <tr key={f.id} className="border-b border-ink-50 last:border-0 hover:bg-hover">
                    <td className="px-4 py-3 font-semibold text-ink">
                      {formatPeriod(f.period, locale)}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={statusTone(f.status)}>
                        {t(`tax.status.${f.status}` as Parameters<typeof t>[0])}
                      </Badge>
                    </td>
                    <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                      {formatMoney(f.outputVatMinor, f.currency, locale)}
                    </td>
                    <td className="tnum px-4 py-3 text-right font-mono text-ink-2">
                      {formatMoney(f.inputVatMinor, f.currency, locale)}
                    </td>
                    <td className="tnum px-4 py-3 text-right font-mono font-semibold text-ink">
                      {formatMoney(f.netMinor, f.currency, locale)}{' '}
                      <span className="text-[11px] font-normal text-ink-3">
                        {t(`tax.direction.${f.netDirection}` as Parameters<typeof t>[0])}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        )}
      </div>
    </div>
  )
}
