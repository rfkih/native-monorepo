import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Download, Printer, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatAmount } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { useCashFlow } from './api'
import { downloadCsv, EntityScope, LineSection, PeriodNav, StatementEmptyState, SummaryCard } from './parts'

/**
 * Cash Flow Statement (Arus Kas) — the indirect method, derived from the GL. Net income + the
 * working-capital adjustments = net cash from operating; investing / financing are structured for
 * later. The derived net change in cash reconciles exactly to the cash-account movement.
 */
export function CashFlow() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)

  const [period, setPeriod] = useState(currentPeriod())

  const query = useCashFlow({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    period,
    enabled: !!company,
  })

  if (!company) {
    return (
      <StatementEmptyState title={t('statements.noCompany')} hint={t('statements.noCompanyHint')} />
    )
  }

  const data = query.data ?? null
  const currency = data?.currency ?? company.baseCurrency
  const showEmpty = !query.isLoading && !query.isError && data == null

  const operatingRows = data
    ? [
        { accountCode: '', label: t('statements.cashFlow.netIncome'), amountMinor: data.netIncomeMinor },
        ...data.operatingLines.map((l) => ({ accountCode: l.accountCode, amountMinor: l.amountMinor })),
      ]
    : []

  const exportCsv = () => {
    if (!data) return
    downloadCsv(`cash-flow-${period}.csv`, [
      [company.name, t('statements.scopeAllUnits')],
      [t('statements.cashFlow.title'), period, currency],
      [],
      [t('statements.cashFlow.operating')],
      [t('statements.cashFlow.netIncome'), data.netIncomeMinor],
      ...data.operatingLines.map((l) => [l.accountCode, l.amountMinor]),
      [t('statements.cashFlow.fromOperating'), data.cashFromOperatingMinor],
      [],
      [t('statements.cashFlow.investing')],
      ...data.investingLines.map((l) => [
        l.accountCode === 'DISPOSAL_PROCEEDS'
          ? t('statements.cashFlow.disposalProceeds')
          : l.accountCode,
        l.amountMinor,
      ]),
      [t('statements.cashFlow.fromInvesting'), data.cashFromInvestingMinor],
      [],
      [t('statements.cashFlow.financing')],
      ...data.financingLines.map((l) => [l.accountCode, l.amountMinor]),
      [t('statements.cashFlow.fromFinancing'), data.cashFromFinancingMinor],
      [],
      [t('statements.cashFlow.netChange'), data.netChangeInCashMinor],
    ])
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <EntityScope name={company.name} scope={t('statements.scopeAllUnits')} />
          <h1 className="font-display text-[28px] font-extrabold tracking-[-0.02em] text-ink">
            {t('statements.cashFlow.title')}
          </h1>
          <p className="mt-1.5 text-[15px] text-ink-3">{t('statements.cashFlow.subtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2.5 print:hidden">
          <PeriodNav
            period={period}
            locale={locale}
            onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
            onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
            prevLabel={t('statements.prevPeriod')}
            nextLabel={t('statements.nextPeriod')}
          />
          <Button variant="outline" onClick={() => window.print()}>
            <Printer className="size-[15px]" aria-hidden />
            {t('statements.print')}
          </Button>
          <Button onClick={exportCsv} disabled={!data}>
            <Download className="size-[15px]" aria-hidden />
            {t('statements.export')}
          </Button>
        </div>
      </div>

      {data?.usesIllustrativeRules ? (
        <div>
          <Badge tone="amber">
            <TriangleAlert className="size-3" /> {t('statements.illustrative')}
          </Badge>
        </div>
      ) : null}

      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">{t('statements.error')}</Card>
      ) : showEmpty ? (
        <StatementEmptyState title={t('statements.noData')} hint={t('statements.noDataHint')} />
      ) : query.isLoading && !data ? (
        <Card className="flex justify-center p-10 text-emerald">
          <Spinner />
        </Card>
      ) : data ? (
        <>
          <div className="grid gap-4 sm:grid-cols-4">
            <SummaryCard
              chipClass="bg-brand-500"
              label={t('statements.cashFlow.fromOperating')}
              value={formatMoney(data.cashFromOperatingMinor, currency, locale)}
            />
            <SummaryCard
              chipClass="bg-ink-300"
              label={t('statements.cashFlow.fromInvesting')}
              value={formatMoney(data.cashFromInvestingMinor, currency, locale)}
            />
            <SummaryCard
              chipClass="bg-ink-300"
              label={t('statements.cashFlow.fromFinancing')}
              value={formatMoney(data.cashFromFinancingMinor, currency, locale)}
            />
            <SummaryCard
              chipClass="bg-profit"
              label={t('statements.cashFlow.netChange')}
              value={formatMoney(data.netChangeInCashMinor, currency, locale)}
              valueClass={data.netChangeInCashMinor >= 0 ? 'text-profit-ink' : 'text-loss'}
              emphatic
            />
          </div>

          <div className="grid gap-5 lg:grid-cols-2">
            <Card className="p-6">
              <LineSection
                heading={t('statements.cashFlow.operating')}
                lines={operatingRows}
                totalLabel={t('statements.cashFlow.fromOperating')}
                totalMinor={data.cashFromOperatingMinor}
                currency={currency}
                locale={locale}
                emptyLabel={t('statements.noLines')}
                format={formatAmount}
              />
            </Card>
            <div className="flex flex-col gap-5">
              <Card className="p-6">
                <LineSection
                  heading={t('statements.cashFlow.investing')}
                  lines={data.investingLines.map((l) => ({
                    accountCode: l.accountCode,
                    // The disposal-proceeds line is a synthetic marker, not a chart account —
                    // render it with its localized label (ADR 0022).
                    label:
                      l.accountCode === 'DISPOSAL_PROCEEDS'
                        ? t('statements.cashFlow.disposalProceeds')
                        : undefined,
                    amountMinor: l.amountMinor,
                  }))}
                  totalLabel={t('statements.cashFlow.fromInvesting')}
                  totalMinor={data.cashFromInvestingMinor}
                  currency={currency}
                  locale={locale}
                  emptyLabel={t('statements.cashFlow.noInvesting')}
                  format={formatAmount}
                />
              </Card>
              <Card className="p-6">
                <LineSection
                  heading={t('statements.cashFlow.financing')}
                  lines={data.financingLines.map((l) => ({
                    accountCode: l.accountCode,
                    amountMinor: l.amountMinor,
                  }))}
                  totalLabel={t('statements.cashFlow.fromFinancing')}
                  totalMinor={data.cashFromFinancingMinor}
                  currency={currency}
                  locale={locale}
                  emptyLabel={t('statements.cashFlow.noFinancing')}
                  format={formatAmount}
                />
              </Card>
            </div>
          </div>

          {/* Reconciliation: the derived net change equals the actual cash-account movement. */}
          <Card className="flex flex-wrap items-center justify-between gap-3 p-5">
            <div className="flex items-center gap-2 text-sm text-ink-2">
              {data.reconciled ? (
                <Badge tone="profit">
                  <Check className="size-3" /> {t('statements.cashFlow.reconciled')}
                </Badge>
              ) : (
                <Badge tone="loss">
                  <TriangleAlert className="size-3" /> {t('statements.cashFlow.notReconciled')}
                </Badge>
              )}
              <span>{t('statements.cashFlow.reconcileHint')}</span>
            </div>
            <div className="tnum font-mono text-sm font-semibold text-ink">
              {formatMoney(data.cashMovementMinor, currency, locale)}
            </div>
          </Card>
        </>
      ) : null}
    </div>
  )
}
