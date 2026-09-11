import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Download, Printer, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney, formatAmount } from '@/lib/money'
import { printCurrentPage } from '@/lib/nativeShell'
import { currentPeriod, formatPeriod, shiftPeriod } from '@/lib/period'
import { useBalanceSheet } from './api'
import { downloadCsv } from '@/lib/csv'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { Laporan } from './Laporan'
import { balanceSheetCsv } from './statementsCsv'
import { accountLabel } from './accountLabels'
import { displayBalanceSheet, type BalanceDisplayLine } from './balanceSheetView'
import {
  EntityScope,
  EquationRow,
  LineSection,
  PeriodNav,
  StatementEmptyState,
  type DisplayLine,
} from './parts'

/**
 * Balance Sheet (Neraca) — rebuilt around the question an owner actually opens it with.
 *
 * The previous design led with the balance check: the largest element on the page, saying "nothing
 * is wrong" in every normal case, in an alphabet (Δ) most readers don't have. It now leads with NET
 * WORTH — the one figure the statement exists to produce — followed by the equation that produces
 * it (owned − owed = yours), so the relationship between the three sections is the layout rather
 * than something the reader has to already know. The check survives as a chip, and swells back into
 * a full banner only when the sheet does NOT balance, which is when it deserves the room.
 *
 * Three further rules, all in `balanceSheetView.ts`: assets are ordered by how quickly each turns
 * into money (the API returns them by account code, which buries cash), rows worth nothing are
 * hidden behind a reveal, and an asset that has gone negative is flagged instead of rendering in
 * the same ink as every sound figure.
 */
export function BalanceSheet() {
  const { t, i18n } = useTranslation()
  const { company } = useSession()
  const locale = localeOf(i18n.language)

  const isPhone = useIsPhone()
  const [asOf, setAsOf] = useState(currentPeriod())
  // Zero-balance rows are hidden by default. One switch for the whole statement — each section
  // still states its own count, so nothing is hidden without saying so where it happened.
  const [showZeros, setShowZeros] = useState(false)

  const query = useBalanceSheet({
    companyId: company?.companyId ?? '',
    actor: company?.actor ?? '',
    asOf,
    enabled: !!company,
  })

  if (isPhone) return <Laporan tab="bs" />

  if (!company) {
    return (
      <StatementEmptyState
        title={t('statements.noCompany')}
        hint={t('statements.noCompanyHint')}
      />
    )
  }

  const data = query.data ?? null
  const currency = data?.currency ?? company.baseCurrency
  const showEmpty = !query.isLoading && !query.isError && data == null

  const totalAssets = data?.totalAssetsMinor ?? 0
  const totalLiabilities = data?.totalLiabilitiesMinor ?? 0
  const totalEquity = data?.totalEquityMinor ?? 0
  const delta = totalAssets - (data?.totalLiabilitiesAndEquityMinor ?? 0)
  const balanced = delta === 0

  // Every display decision — equipment netted to book value, liquidity groups, impossible balances
  // flagged (assets only), zero rows kept in the DOM for the printout, the synthetic profit row named
  // instead of coded — is one pure step shared with the phone screen (balanceSheetView.ts), so the
  // two can never disagree on a row. The CSV export reads `data.assetLines` untouched.
  const view = displayBalanceSheet(data ?? { assetLines: [], liabilityLines: [], equityLines: [] }, { showZeros })
  const flagged = view.flagged
  const toLine = (l: BalanceDisplayLine): DisplayLine => ({
    accountCode: l.accountCode,
    label: l.labelKey ? t(l.labelKey) : undefined,
    amountMinor: l.amountMinor,
    flagged: l.flagged,
    printOnly: l.printOnly,
  })
  const assetGroups = view.assetGroups.map((g) => ({
    label: t(g.labelKey),
    lines: g.lines.map(toLine),
    subtotalMinor: g.subtotalMinor,
  }))

  /** The "n accounts worth nothing are hidden · Show" line under a section that has any. */
  const zeroFootnote = (hiddenCount: number) =>
    hiddenCount === 0 ? undefined : (
      <p className="text-xs text-ink-3 print:hidden">
        {/* The sentence tracks the state — it said "hidden" even after the reader revealed them. */}
        {showZeros
          ? hiddenCount === 1
            ? t('statements.zeroShownOne')
            : t('statements.zeroShownMany', { count: hiddenCount })
          : hiddenCount === 1
            ? t('statements.zeroHiddenOne')
            : t('statements.zeroHiddenMany', { count: hiddenCount })}{' '}
        <button
          type="button"
          onClick={() => setShowZeros((v) => !v)}
          className="font-semibold text-emerald-2 underline-offset-2 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          {showZeros ? t('statements.zeroHide') : t('statements.zeroShow')}
        </button>
      </p>
    )

  // Built by the shared, tested builder (statementsCsv.ts): the FORMAL wording, every line including
  // the zeros — the spreadsheet is the accountant's artefact. One source with the phone Export sheet.
  const exportCsv = () => {
    if (!data) return
    const file = balanceSheetCsv({ translate: t, companyName: company.name }, data)
    downloadCsv(file.filename, file.rows)
  }

  return (
    <div className="flex flex-col gap-5">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <EntityScope name={company.name} scope={t('statements.scopeAllUnits')} />
          <h1 className="font-display text-2xl font-extrabold tracking-[-0.02em] text-ink">
            {t('statements.balanceTitle')}
          </h1>
        </div>
        <div className="flex flex-wrap items-center gap-2.5 print:hidden max-sm:w-full max-sm:justify-end">
          <div className="max-sm:w-full">
            <PeriodNav
              period={asOf}
              locale={locale}
              onPrev={() => setAsOf((p) => shiftPeriod(p, -1))}
              onNext={() => setAsOf((p) => shiftPeriod(p, 1))}
              prevLabel={t('statements.prevPeriod')}
              nextLabel={t('statements.nextPeriod')}
            />
          </div>
          {/* Icon-only on a phone: with the stepper on its own row above, the two actions pair up
              at the right instead of one of them being orphaned on a line of its own. */}
          <Button
            variant="outline"
            onClick={() => printCurrentPage('balance-sheet')}
            aria-label={t('statements.print')}
          >
            <Printer className="size-[15px]" aria-hidden />
            <span className="max-sm:hidden">{t('statements.print')}</span>
          </Button>
          <Button onClick={exportCsv} disabled={!data} aria-label={t('statements.export')}>
            <Download className="size-[15px]" aria-hidden />
            <span className="max-sm:hidden">{t('statements.export')}</span>
          </Button>
        </div>
      </div>

      {/* Illustrative badge */}
      {data?.usesIllustrativeRules ? (
        <div>
          <Badge tone="amber">
            <TriangleAlert className="size-3" /> {t('statements.illustrative')}
          </Badge>
        </div>
      ) : null}

      {/* Error / empty / content */}
      {query.isError ? (
        <Card className="p-8 text-center text-sm text-loss">{t('statements.error')}</Card>
      ) : showEmpty ? (
        <StatementEmptyState title={t('statements.noData')} hint={t('statements.noDataHint')} />
      ) : query.isLoading && !data ? (
        <>
          <Skeleton className="h-[132px] rounded-card" />
          <Skeleton className="h-[86px] rounded-card" />
          <div className="grid grid-cols-[minmax(0,1fr)] gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <ListSkeleton rows={5} className="rounded-[20px]" />
            <ListSkeleton rows={5} className="rounded-[20px]" />
          </div>
        </>
      ) : (
        <>
          {/* Net worth — the answer the statement exists to produce, in the position the balance
              check used to occupy. */}
          <Card className="border-emerald-line bg-emerald-tint p-6 print:break-inside-avoid">
            <div className="text-2xs font-bold uppercase tracking-[0.08em] text-emerald-2">
              {t('statements.netWorth')} · {formatPeriod(asOf, locale)}
            </div>
            <div className="tnum mt-1.5 font-mono text-3xl font-bold leading-tight tracking-[-0.02em] text-ink print:text-2xl">
              {formatMoney(totalEquity, currency, locale)}
            </div>
          </Card>

          {/* Where it comes from: owned − owed = yours. The equation IS the layout, so the reader
              doesn't have to already know how the three sections relate. */}
          <Card className="p-5 print:break-inside-avoid">
            <div className="flex max-w-[460px] flex-col gap-2.5">
              <EquationRow
                label={t('statements.plain.assets')}
                value={formatMoney(totalAssets, currency, locale)}
              />
              <EquationRow
                op="−"
                label={t('statements.plain.liabilities')}
                value={formatMoney(totalLiabilities, currency, locale)}
              />
              {/* Without this line the sum is visibly wrong on an unbalanced sheet — owned − owed
                  only equals yours when delta is zero. Naming the gap keeps the arithmetic true
                  AND puts the discrepancy where the reader is already doing the subtraction. */}
              {balanced ? null : (
                <EquationRow
                  op="−"
                  label={t('statements.plain.difference')}
                  value={formatMoney(delta, currency, locale)}
                  tone="warning"
                />
              )}
              <EquationRow
                op="="
                label={t('statements.plain.equity')}
                value={formatMoney(totalEquity, currency, locale)}
                answer
              />
            </div>
            {balanced ? (
              <div className="mt-4">
                <Badge tone="profit">
                  <Check className="size-3" /> {t('statements.checksOut')}
                </Badge>
              </div>
            ) : null}
          </Card>

          {/* The check only takes the room when it has something to report. */}
          {!balanced ? (
            <div className="flex items-center gap-3.5 rounded-[20px] border border-warning/30 bg-tint-warning px-5 py-[18px]">
              <span className="grid size-8 shrink-0 place-items-center rounded-full bg-warning">
                <TriangleAlert className="size-4 text-white" aria-hidden />
              </span>
              <div className="min-w-0 flex-1">
                <div className="text-base font-bold text-ink">
                  {t('statements.unbalancedTitle')}
                </div>
              </div>
              <span className="tnum shrink-0 font-mono text-base font-bold text-amber-2">
                {t('statements.difference', {
                  amount: formatMoney(delta, currency, locale),
                })}
              </span>
            </div>
          ) : null}

          {/* A figure that cannot be real. The UI can't fix the ledger, but staying silent is a
              choice too — this is the only row on the page that asks for action. */}
          {flagged.length > 0 ? (
            <div className="flex items-start gap-3 rounded-[20px] border border-loss/30 bg-tint-loss px-5 py-4 print:break-inside-avoid">
              <span className="mt-0.5 grid size-[22px] shrink-0 place-items-center rounded-full bg-loss text-sm font-bold text-white">
                !
              </span>
              <div className="min-w-0">
                <div className="text-sm font-bold text-ink">
                  {flagged.length === 1
                    ? t('statements.unnatural.oneTitle', {
                        name:
                          accountLabel(t, flagged[0].accountCode) ?? flagged[0].accountCode,
                        amount: formatMoney(
                          Math.abs(flagged[0].balanceMinor),
                          currency,
                          locale,
                        ),
                      })
                    : t('statements.unnatural.manyTitle', { count: flagged.length })}
                </div>
                <p className="mt-0.5 max-w-[62ch] text-xs text-ink-2">
                  {t('statements.unnatural.body')}
                </p>
              </div>
            </div>
          ) : null}

          {/* Account tables — assets on one side, the two claims on it on the other. */}
          <div className="grid grid-cols-[minmax(0,1fr)] gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <Card className="p-6">
              <LineSection
                heading={t('statements.plain.assets')}
                groups={assetGroups}
                totalLabel={t('statements.plain.totalAssets')}
                totalMinor={totalAssets}
                currency={currency}
                locale={locale}
                emptyLabel={t('statements.noLines')}
                format={formatAmount}
                footnote={zeroFootnote(view.hiddenAssets)}
              />
            </Card>
            <Card className="p-6">
              <LineSection
                heading={t('statements.plain.liabilities')}
                lines={view.liabilityLines.map(toLine)}
                totalLabel={t('statements.plain.totalLiabilities')}
                totalMinor={totalLiabilities}
                currency={currency}
                locale={locale}
                emptyLabel={t('statements.noLines')}
                format={formatAmount}
                footnote={zeroFootnote(view.hiddenLiabilities)}
              />
              <div className="mt-6">
                <LineSection
                  heading={t('statements.plain.equity')}
                  lines={view.equityLines.map(toLine)}
                  totalLabel={t('statements.plain.totalEquity')}
                  totalMinor={totalEquity}
                  currency={currency}
                  locale={locale}
                  emptyLabel={t('statements.noLines')}
                  format={formatAmount}
                  footnote={zeroFootnote(view.hiddenEquity)}
                />
              </div>
            </Card>
          </div>
        </>
      )}
    </div>
  )
}
