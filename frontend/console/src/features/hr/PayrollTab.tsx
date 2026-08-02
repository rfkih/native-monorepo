/**
 * Payroll tab of the org-unit hub. The statutory rates behind every run are ILLUSTRATIVE
 * PLACEHOLDERS until OFFICIAL rows are seeded — a persistent amber banner says so whenever the
 * provenance is not OFFICIAL (never hide it).
 *
 * A payroll run is COMPANY-WIDE, never per-unit: finance treats (period, run_seq) as a
 * supersession chain — a higher run_seq REVERSES every earlier ACTIVE run's labor postings for
 * the period and replaces them. A partial (per-unit) run would therefore erase the other units'
 * labor cost off the ledger, so the Run button here always includes EVERY payable employee of the
 * company regardless of which unit's tab it sits on (review-critical; do not "optimize" back to
 * unit scope). Re-running is the correction flow: it supersedes the period's earlier runs.
 * Salary is PII — payslip lines render masked.
 */

import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronRight, Download, Play, TriangleAlert } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { Field, TextInput } from '@/components/ui/Field'
import { EmptyState, KpiTile, PeriodNav } from '@/features/_shared/financeUi'
import { DialogOverlay } from '@/features/org/parts'
import type { OrgUnit } from '@/features/org/api'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { currentPeriod, shiftPeriod } from '@/lib/period'
import { PayrollSetupTab } from './PayrollSetupTab'
import {
  downloadPayrollBankFile,
  parseWorkInputs,
  useEmployees,
  useLeaveRequests,
  useOvertimeEntries,
  usePayrollLiabilities,
  usePayrollRuns,
  usePayrollSetup,
  usePayslip,
  usePayslipIndex,
  useRunAllocations,
  useRunPayroll,
  useSeedIllustrative,
  useSettlePayrollLiability,
  type PayrollLiabilityBucket,
  type PayrollRunSummary,
  type SettlementKind,
} from './api'

type PayrollView = 'runs' | 'setup'

export function PayrollTab({
  units,
  companyId,
  actor,
  baseCurrency,
  locale,
  onNavigateToAttendance,
}: {
  units: OrgUnit[]
  companyId: string
  actor: string
  baseCurrency: string
  locale: string
  onNavigateToAttendance?: () => void
}) {
  const { t } = useTranslation()
  const [period, setPeriod] = useState(currentPeriod())
  const [runDialogType, setRunDialogType] = useState<'REGULAR' | 'THR' | null>(null)
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null)
  const [view, setView] = useState<PayrollView>('runs')
  // Track P Phase P8 — a client-side run_type filter over the already-fetched period's runs.
  const [runTypeFilter, setRunTypeFilter] = useState<'ALL' | 'REGULAR' | 'THR'>('ALL')

  const setup = usePayrollSetup({ companyId, actor, enabled: true })
  const seed = useSeedIllustrative({ companyId, actor })

  // Track P Phase P7 — the pre-run checklist: pending (undecided) leave/overtime requests block
  // Run entirely (the server enforces this too, 409 PendingWorkEntriesException; this is the
  // pre-emptive UI signal so a manager sees it BEFORE attempting to run).
  const pendingLeave = useLeaveRequests({ companyId, actor, status: 'SUBMITTED', size: 1, enabled: true })
  const pendingOvertime = useOvertimeEntries({
    companyId,
    actor,
    status: 'SUBMITTED',
    size: 1,
    enabled: true,
  })
  const pendingCount = (pendingLeave.data?.totalElements ?? 0) + (pendingOvertime.data?.totalElements ?? 0)

  // COMPANY-WIDE scope (see the file javadoc: a partial run would supersede-and-erase the other
  // units' labor cost in finance). ACTIVE employees only; only those WITH compensation are payable
  // (an employee without a covering package would silently compute a zero base).
  const employeesQuery = useEmployees({ companyId, actor, orgUnitIds: [], enabled: true })
  const runsQuery = usePayrollRuns({ companyId, actor, period, enabled: true })

  const scoped = useMemo(() => {
    const byId = new Map<string, { hasCompensation: boolean }>()
    for (const row of employeesQuery.data ?? []) {
      if (row.status !== 'ACTIVE') continue
      const existing = byId.get(row.employeeId)
      byId.set(row.employeeId, {
        hasCompensation: (existing?.hasCompensation ?? false) || row.hasCompensation,
      })
    }
    return byId
  }, [employeesQuery.data])
  const payableIds = [...scoped.entries()].filter(([, v]) => v.hasCompensation).map(([id]) => id)

  // The completeness gate checks every ACTIVE business unit's period seal (company-wide run).
  const buIds = useMemo(
    () => units.filter((u) => u.type === 'BUSINESS_UNIT' && u.active).map((u) => u.id),
    [units],
  )
  const allRuns = runsQuery.data ?? []
  const runs =
    runTypeFilter === 'ALL' ? allRuns : allRuns.filter((r) => r.runType === runTypeFilter)
  const selectedRun = runs.find((r) => r.id === selectedRunId) ?? runs[0] ?? null

  const showIllustrativeBanner = !!setup.data && setup.data.provenance !== 'OFFICIAL'

  return (
    <div className="flex flex-col gap-4">
      {/* Persistent illustrative banner — visible whenever provenance is not OFFICIAL. */}
      {showIllustrativeBanner ? (
        <Card className="flex items-start gap-3 border-amber/40 bg-amber-tint p-4">
          <TriangleAlert className="mt-0.5 size-5 shrink-0 text-amber" aria-hidden="true" />
          <div>
            <p className="text-sm font-semibold text-amber">
              {t('hr.payroll.illustrativeBanner.title')}
            </p>
            <p className="mt-0.5 text-xs leading-relaxed text-amber/90">
              {t('hr.payroll.illustrativeBanner.body')}
            </p>
          </div>
        </Card>
      ) : null}

      {setup.isLoading ? (
        <Card className="p-10 text-center">
          <Spinner className="mx-auto text-brand-500" />
        </Card>
      ) : !setup.data?.seeded ? (
        /* Setup gate — payroll needs the pay-component catalog + statutory rules first. */
        <Card className="p-8 text-center">
          <p className="text-[15px] font-semibold text-ink">{t('hr.payroll.setup.title')}</p>
          <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-ink-3">
            {t('hr.payroll.setup.body')}
          </p>
          <Button
            type="button"
            className="mt-5"
            onClick={() => seed.mutate(baseCurrency)}
            disabled={seed.isPending}
          >
            {seed.isPending ? t('hr.payroll.setup.seeding') : t('hr.payroll.setup.seed')}
          </Button>
          {seed.isError ? (
            <p className="mt-3 text-sm text-loss">{t('hr.assign.error')}</p>
          ) : null}
        </Card>
      ) : (
        <>
          {/* Runs / Setup sub-view — statutory-rule administration lives beside the run history
              (Track P phase P2, ADR 0031), not as a separate org-hub tab. */}
          <Segmented<PayrollView>
            options={[
              { value: 'runs', label: t('hr.payroll.view.runs') },
              { value: 'setup', label: t('hr.payroll.view.setup') },
            ]}
            value={view}
            onChange={setView}
            ariaLabel={t('hr.payroll.view.label')}
          />

          {view === 'setup' ? (
            <PayrollSetupTab companyId={companyId} actor={actor} locale={locale} />
          ) : (
            <>
              {/* Period + scope + run */}
              <div className="flex flex-wrap items-center justify-between gap-3">
                <PeriodNav
                  period={period}
                  locale={locale}
                  onPrev={() => setPeriod((p) => shiftPeriod(p, -1))}
                  onNext={() => setPeriod((p) => shiftPeriod(p, 1))}
                  prevLabel={t('orgHub.overview.prevMonth')}
                  nextLabel={t('orgHub.overview.nextMonth')}
                />
                <div className="flex items-center gap-3">
                  <span className="text-sm text-ink-3">
                    {t('hr.payroll.scope.count', {
                      total: scoped.size,
                      withComp: payableIds.length,
                    })}
                  </span>
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setRunDialogType('THR')}
                    disabled={payableIds.length === 0 || pendingCount > 0}
                  >
                    <Play className="size-4" />
                    {t('hr.payroll.run.ctaThr')}
                  </Button>
                  <Button
                    type="button"
                    onClick={() => setRunDialogType('REGULAR')}
                    disabled={payableIds.length === 0 || pendingCount > 0}
                  >
                    <Play className="size-4" />
                    {t('hr.payroll.run.cta')}
                  </Button>
                </div>
              </div>

              {scoped.size > 0 && payableIds.length === 0 ? (
                <EmptyState
                  title={t('hr.payroll.scope.noneWithComp')}
                  hint={t('hr.payroll.scope.noneHint')}
                />
              ) : null}

              {/* Track P Phase P7 — the pre-run checklist: pending leave/overtime requests block
                  Run entirely until a manager decides them (server-enforced too, 409). */}
              {pendingCount > 0 ? (
                <Card className="flex items-start gap-3 border-amber/40 bg-amber-tint p-4">
                  <TriangleAlert className="mt-0.5 size-5 shrink-0 text-amber" aria-hidden="true" />
                  <div className="flex-1">
                    <p className="text-sm font-semibold text-amber">
                      {t('hr.payroll.pending.title', { count: pendingCount })}
                    </p>
                    <p className="mt-0.5 text-xs leading-relaxed text-amber/90">
                      {t('hr.payroll.pending.body')}
                    </p>
                  </div>
                  {onNavigateToAttendance ? (
                    <Button type="button" variant="outline" onClick={onNavigateToAttendance}>
                      {t('hr.payroll.pending.cta')}
                    </Button>
                  ) : null}
                </Card>
              ) : null}

              {/* Run history */}
              {runsQuery.isLoading ? (
                <Card className="p-10 text-center">
                  <Spinner className="mx-auto text-brand-500" />
                </Card>
              ) : allRuns.length === 0 ? (
                <EmptyState
                  title={t('hr.payroll.history.empty')}
                  hint={t('hr.payroll.history.emptyHint')}
                />
              ) : (
                <>
                  {allRuns.some((r) => r.runType === 'THR') ? (
                    <Segmented<'ALL' | 'REGULAR' | 'THR'>
                      options={[
                        { value: 'ALL', label: t('hr.payroll.history.filterAll') },
                        { value: 'REGULAR', label: t('hr.payroll.history.filterRegular') },
                        { value: 'THR', label: t('hr.payroll.history.filterThr') },
                      ]}
                      value={runTypeFilter}
                      onChange={setRunTypeFilter}
                      ariaLabel={t('hr.payroll.history.filterLabel')}
                    />
                  ) : null}
                  <Card className="rounded-[20px] p-2.5">
                    {runs.map((run) => (
                      <button
                        key={run.id}
                        type="button"
                        onClick={() => setSelectedRunId(run.id)}
                        className={cn(
                          'flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors hover:bg-hover',
                          selectedRun?.id === run.id && 'bg-emerald-tint/50',
                        )}
                      >
                        <span className="text-[14px] font-semibold text-ink">
                          {t('hr.payroll.history.runLabel', { seq: run.runSeq })}
                        </span>
                        {run.runType === 'THR' ? (
                          <Badge tone="info">{t('hr.payroll.history.thrBadge')}</Badge>
                        ) : null}
                        {run.period.endsWith('-12') && !run.usesIllustrativeRules ? (
                          <Badge tone="info">{t('hr.payroll.history.trueUpBadge')}</Badge>
                        ) : null}
                        {run.usesIllustrativeRules ? (
                          <Badge tone="amber">{t('hr.payroll.illustrativeBanner.badge')}</Badge>
                        ) : null}
                        <span className="text-xs text-ink-3">
                          {run.postedAt
                            ? new Intl.DateTimeFormat(locale, {
                                dateStyle: 'medium',
                                timeStyle: 'short',
                              }).format(new Date(run.postedAt))
                            : run.status === 'FAILED'
                              ? t('hr.payroll.history.failedLabel')
                              : run.status}
                        </span>
                        <span className="tnum ml-auto font-mono text-sm font-semibold text-ink">
                          {formatMoney(run.netTotalMinor, run.baseCurrency, locale)}
                        </span>
                      </button>
                    ))}
                  </Card>

                  {selectedRun ? (
                    <RunDetail
                      run={selectedRun}
                      units={units}
                      companyId={companyId}
                      actor={actor}
                      locale={locale}
                    />
                  ) : null}
                </>
              )}

              {runs.length > 0 ? (
                <p className="text-xs leading-relaxed text-ink-3">
                  <TriangleAlert className="mr-1 inline size-3.5 text-amber" aria-hidden="true" />
                  {t('hr.payroll.rerun.warning')}
                </p>
              ) : null}
            </>
          )}
        </>
      )}

      {runDialogType ? (
        <RunPayrollDialog
          period={period}
          runType={runDialogType}
          employeeIds={payableIds}
          buIds={buIds}
          baseCurrency={baseCurrency}
          companyId={companyId}
          actor={actor}
          onClose={() => setRunDialogType(null)}
        />
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Selected-run detail: KPIs, payslips (masked), labor cost by outlet
// ---------------------------------------------------------------------------

function RunDetail({
  run,
  units,
  companyId,
  actor,
  locale,
}: {
  run: PayrollRunSummary
  units: OrgUnit[]
  companyId: string
  actor: string
  locale: string
}) {
  const { t } = useTranslation()
  const auth = useAuth()
  const isOwner = hasAnyRole(auth.roles, 'owner')
  const [openEmployee, setOpenEmployee] = useState<string | null>(null)
  const [downloadingBankFile, setDownloadingBankFile] = useState(false)
  const [bankFileError, setBankFileError] = useState(false)
  const allocations = useRunAllocations({ companyId, actor, runId: run.id, enabled: true })
  const payslips = usePayslipIndex({ companyId, actor, runId: run.id, enabled: true })
  const lines = usePayslip({
    companyId,
    actor,
    runId: run.id,
    employeeId: openEmployee,
    enabled: !!openEmployee,
  })

  const unitName = (id: string) => units.find((u) => u.id === id)?.name

  const allocationTotal = (allocations.data ?? []).reduce((sum, a) => sum + a.amountMinor, 0)

  async function handleDownloadBankFile() {
    setBankFileError(false)
    setDownloadingBankFile(true)
    try {
      await downloadPayrollBankFile({
        companyId,
        actor,
        runId: run.id,
        period: run.period,
        runSeq: run.runSeq,
      })
    } catch {
      setBankFileError(true)
    } finally {
      setDownloadingBankFile(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      {/* Owner-only net-pay bank file (Track P phase P5) — bank-account PII, gateway-gated too. */}
      {isOwner && run.status === 'POSTED' ? (
        <div className="flex items-center justify-end gap-2">
          {bankFileError ? (
            <span className="text-xs text-loss">{t('hr.payroll.bankFile.error')}</span>
          ) : null}
          <Button
            type="button"
            variant="outline"
            onClick={() => void handleDownloadBankFile()}
            disabled={downloadingBankFile}
          >
            <Download className="size-4" />
            {downloadingBankFile
              ? t('hr.payroll.bankFile.downloading')
              : t('hr.payroll.bankFile.cta')}
          </Button>
        </div>
      ) : null}

      {/* Company totals */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label={t('hr.payroll.kpi.gross')}
          minor={run.grossTotalMinor}
          currency={run.baseCurrency}
          locale={locale}
          loading={false}
        />
        <KpiTile
          label={t('hr.payroll.kpi.deductions')}
          minor={run.employeeDeductionTotalMinor}
          currency={run.baseCurrency}
          locale={locale}
          loading={false}
        />
        <KpiTile
          label={t('hr.payroll.kpi.employer')}
          minor={run.employerContributionTotalMinor}
          currency={run.baseCurrency}
          locale={locale}
          loading={false}
        />
        <KpiTile
          label={t('hr.payroll.kpi.net')}
          minor={run.netTotalMinor}
          currency={run.baseCurrency}
          locale={locale}
          loading={false}
          emphatic
        />
      </div>

      {/* Payslips — masked; expand per employee */}
      <Card className="p-4">
        <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
          {t('hr.payroll.payslips.title')}
        </p>
        <div className="mt-2">
          {(payslips.data ?? []).map((row) => (
            <div key={row.employeeId}>
              <button
                type="button"
                onClick={() =>
                  setOpenEmployee((cur) => (cur === row.employeeId ? null : row.employeeId))
                }
                className="flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left hover:bg-hover"
              >
                {openEmployee === row.employeeId ? (
                  <ChevronDown className="size-3.5 text-ink-3" aria-hidden="true" />
                ) : (
                  <ChevronRight className="size-3.5 text-ink-3" aria-hidden="true" />
                )}
                <span className="text-sm font-semibold text-ink">{row.fullName}</span>
                <span className="text-xs text-ink-3">
                  {t('hr.payroll.payslips.lines', { count: row.lineCount })}
                </span>
              </button>
              {openEmployee === row.employeeId ? (
                <div className="mb-2 ml-7 overflow-x-auto">
                  {lines.isLoading ? (
                    <Spinner className="my-2 text-brand-500" />
                  ) : (
                    <table className="w-full text-left text-sm">
                      <thead>
                        <tr className="text-[11px] uppercase tracking-wider text-ink-3">
                          <th className="py-1 pr-4 font-semibold">
                            {t('hr.payroll.payslips.component')}
                          </th>
                          <th className="py-1 pr-4 font-semibold">
                            {t('hr.payroll.payslips.kind')}
                          </th>
                          <th className="py-1 font-semibold">
                            {t('hr.payroll.payslips.amount')}
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        {(lines.data ?? []).map((line, i) => (
                          <tr key={`${line.componentKey}-${i}`} className="text-ink-2">
                            <td className="py-1 pr-4 font-mono text-xs">{line.componentKey}</td>
                            <td className="py-1 pr-4 text-xs">{line.kind}</td>
                            {/* Salary PII — always the mask, never an amount. */}
                            <td className="tnum py-1 font-mono text-xs">
                              {line.amountMasked ?? '***'}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              ) : null}
            </div>
          ))}
        </div>
      </Card>

      {/* Labor cost by outlet — aggregated buckets, contribution bars */}
      <Card className="p-4">
        <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
          {t('hr.payroll.labor.title')}
        </p>
        <div className="mt-2 space-y-2">
          {(allocations.data ?? []).map((a) => {
            const share = allocationTotal > 0 ? a.amountMinor / allocationTotal : 0
            return (
              <div key={`${a.outletOrgUnitId}-${a.glAccount}`}>
                <div className="flex items-center justify-between gap-2 text-sm">
                  <span className={cn('truncate', a.unallocated ? 'text-amber' : 'text-ink')}>
                    {a.unallocated
                      ? t('hr.payroll.labor.unallocated')
                      : (unitName(a.outletOrgUnitId) ?? a.glAccount)}
                    <span className="ml-1.5 font-mono text-[11px] text-ink-3">{a.glAccount}</span>
                  </span>
                  <span className="tnum shrink-0 font-mono text-xs font-semibold text-ink">
                    {formatMoney(a.amountMinor, a.currency, locale)}
                  </span>
                </div>
                <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-paper">
                  <div
                    className={cn('h-full rounded-full', a.unallocated ? 'bg-amber' : 'bg-emerald')}
                    style={{ width: `${Math.max(2, Math.round(share * 100))}%` }}
                  />
                </div>
              </div>
            )
          })}
        </div>
      </Card>

      {/* Work inputs — per-employee unpaid days / overtime hours / reimbursement, from the run's
          FROZEN work_inputs_json (Track P Phase P7 reproducibility record). */}
      <WorkInputsPanel run={run} payslipIndex={payslips.data ?? []} locale={locale} />

      {/* Liabilities — five settleable buckets recognised when the run posted (ADR 0032, P5). */}
      <LiabilitiesPanel
        companyId={companyId}
        actor={actor}
        period={run.period}
        payrollRunId={run.id}
        locale={locale}
      />
    </div>
  )
}

// ---------------------------------------------------------------------------
// Work inputs panel — per-employee breakdown from work_inputs_json (Track P Phase P7)
// ---------------------------------------------------------------------------

function WorkInputsPanel({
  run,
  payslipIndex,
  locale,
}: {
  run: PayrollRunSummary
  payslipIndex: { employeeId: string; fullName: string }[]
  locale: string
}) {
  const { t } = useTranslation()
  const breakdown = parseWorkInputs(run.workInputsJson)
  const employeeIds = Object.keys(breakdown)
  if (employeeIds.length === 0) {
    return null
  }
  const nameById = new Map(payslipIndex.map((row) => [row.employeeId, row.fullName]))

  return (
    <Card className="p-4">
      <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('hr.payroll.workInputs.title')}
      </p>
      <div className="mt-2 divide-y divide-line">
        {employeeIds.map((employeeId) => {
          const entry = breakdown[employeeId]
          return (
            <div key={employeeId} className="flex flex-wrap items-center gap-x-4 gap-y-1 py-2 text-sm">
              <span className="min-w-[10rem] font-semibold text-ink">
                {nameById.get(employeeId) ?? employeeId}
              </span>
              {entry.unpaidLeave ? (
                <span className="text-ink-2">
                  {t('hr.payroll.workInputs.unpaidLeave', { days: entry.unpaidLeave.days })}
                </span>
              ) : null}
              {entry.overtime ? (
                <span className="text-ink-2">
                  {t('hr.payroll.workInputs.overtime', {
                    minutes: entry.overtime.weekdayMinutes + entry.overtime.restDayMinutes,
                  })}
                </span>
              ) : null}
              {entry.reimbursement ? (
                <span className="text-ink-2">
                  {t('hr.payroll.workInputs.reimbursement', {
                    amount: formatMoney(
                      entry.reimbursement.amountMinor,
                      entry.reimbursement.currency,
                      locale,
                    ),
                  })}
                </span>
              ) : null}
            </div>
          )
        })}
      </div>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Liabilities panel — five buckets, settle button, settled badge (ADR 0032, Track P phase P5)
// ---------------------------------------------------------------------------

function LiabilitiesPanel({
  companyId,
  actor,
  period,
  payrollRunId,
  locale,
}: {
  companyId: string
  actor: string
  period: string
  payrollRunId: string
  locale: string
}) {
  const { t } = useTranslation()
  const liabilities = usePayrollLiabilities({ companyId, actor, period, enabled: true })
  const settle = useSettlePayrollLiability({ companyId, actor })
  const [settlingKind, setSettlingKind] = useState<SettlementKind | null>(null)

  const run = (liabilities.data ?? []).find((r) => r.payrollRunId === payrollRunId)

  if (liabilities.isLoading) {
    return (
      <Card className="p-10 text-center">
        <Spinner className="mx-auto text-brand-500" />
      </Card>
    )
  }
  // No liability recognised for this run yet (finance's PayrollLiabilitiesPosted consumer may lag
  // the run-posted event briefly), or the run's liability was superseded — nothing to show.
  if (!run) {
    return null
  }

  function handleSettle(kind: SettlementKind) {
    setSettlingKind(kind)
    settle.mutate(
      { runLedgerId: run!.runLedgerId, kind, idempotencyKey: crypto.randomUUID() },
      { onSettled: () => setSettlingKind(null) },
    )
  }

  return (
    <Card className="p-4">
      <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-3">
        {t('hr.payroll.liabilities.title')}
      </p>
      <div className="mt-2 divide-y divide-line">
        {run.buckets.map((bucket) => (
          <LiabilityRow
            key={bucket.kind}
            bucket={bucket}
            locale={locale}
            settling={settlingKind === bucket.kind && settle.isPending}
            onSettle={() => handleSettle(bucket.kind)}
          />
        ))}
      </div>
      {settle.isError ? <p className="mt-2 text-sm text-loss">{t('hr.assign.error')}</p> : null}
    </Card>
  )
}

function LiabilityRow({
  bucket,
  locale,
  settling,
  onSettle,
}: {
  bucket: PayrollLiabilityBucket
  locale: string
  settling: boolean
  onSettle: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center justify-between gap-3 py-2">
      <div>
        <p className="text-sm font-semibold text-ink">
          {t(`hr.payroll.liabilities.kind.${bucket.kind}`)}
        </p>
        {bucket.negative ? (
          <p className="mt-0.5 max-w-md text-xs leading-relaxed text-amber">
            {t('hr.payroll.liabilities.negativeExplanation')}
          </p>
        ) : bucket.settled && bucket.settledAt ? (
          <p className="mt-0.5 text-xs text-ink-3">
            {t('hr.payroll.liabilities.settledAt', {
              date: new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(
                new Date(bucket.settledAt),
              ),
            })}
          </p>
        ) : null}
      </div>
      <div className="flex items-center gap-3">
        <span className="tnum font-mono text-sm font-semibold text-ink">
          {formatMoney(bucket.amountMinor, bucket.currency, locale)}
        </span>
        {bucket.settled ? (
          <Badge tone="profit">{t('hr.payroll.liabilities.settledBadge')}</Badge>
        ) : bucket.negative ? null : (
          <Button
            type="button"
            variant="outline"
            className="h-8 px-3 text-xs"
            onClick={onSettle}
            disabled={settling || bucket.amountMinor === 0}
          >
            {settling
              ? t('hr.payroll.liabilities.settling')
              : t('hr.payroll.liabilities.settleCta')}
          </Button>
        )}
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Run dialog (confirm → POST; completeness-gate failure → loud ungated escape)
// ---------------------------------------------------------------------------

function RunPayrollDialog({
  period,
  runType,
  employeeIds,
  buIds,
  baseCurrency,
  companyId,
  actor,
  onClose,
}: {
  period: string
  runType: 'REGULAR' | 'THR'
  employeeIds: string[]
  buIds: string[]
  baseCurrency: string
  companyId: string
  actor: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [gateFailed, setGateFailed] = useState(false)
  // Track P Phase P8 — the H-7 payment date for a THR run: informational only (not persisted, does
  // not drive proration), required before Run is enabled so the owner records when THR was paid.
  const [holidayDate, setHolidayDate] = useState('')
  const isThr = runType === 'THR'
  const mutation = useRunPayroll({ companyId, actor })

  function run(expectedSourceBusinessIds: string[]) {
    mutation.mutate(
      {
        period,
        employeeIds,
        expectedSourceBusinessIds,
        baseCurrency,
        runType,
        holidayDate: isThr ? holidayDate : undefined,
      },
      {
        onSuccess: onClose,
        onError: (err) => {
          if ((err as { status?: number } | null)?.status === 409) {
            setGateFailed(true)
          }
        },
      },
    )
  }

  return (
    <DialogOverlay onClose={onClose}>
      <div className="space-y-4">
        <h2 className="font-display text-lg font-semibold text-ink">
          {isThr ? t('hr.payroll.run.confirmTitleThr') : t('hr.payroll.run.confirmTitle')}
        </h2>

        {gateFailed ? (
          <>
            <p className="text-sm text-ink-2">{t('hr.payroll.run.gateFailedBody', { period })}</p>
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={onClose}>
                {t('common.cancel')}
              </Button>
              {/* The LOUD escape hatch — an ungated run skips the completeness check. */}
              <Button
                type="button"
                variant="outline"
                className="text-amber hover:bg-amber-tint"
                onClick={() => run([])}
                disabled={mutation.isPending}
              >
                {mutation.isPending ? t('hr.payroll.run.running') : t('hr.payroll.run.runUngated')}
              </Button>
            </div>
          </>
        ) : (
          <>
            <p className="text-sm text-ink-2">
              {isThr
                ? t('hr.payroll.run.confirmBodyThr', { period, count: employeeIds.length })
                : t('hr.payroll.run.confirmBody', { period, count: employeeIds.length })}
            </p>
            {isThr ? (
              <Field label={t('hr.payroll.run.holidayDateLabel')} htmlFor="thr-holiday-date">
                <TextInput
                  id="thr-holiday-date"
                  type="date"
                  value={holidayDate}
                  onChange={(e) => setHolidayDate(e.target.value)}
                  required
                />
              </Field>
            ) : null}
            {mutation.isError ? (
              <p className="text-sm text-loss">{t('hr.assign.error')}</p>
            ) : null}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={onClose}>
                {t('common.cancel')}
              </Button>
              <Button
                type="button"
                onClick={() => run(buIds)}
                disabled={mutation.isPending || (isThr && !holidayDate)}
              >
                {mutation.isPending
                  ? t('hr.payroll.run.running')
                  : isThr
                    ? t('hr.payroll.run.ctaThr')
                    : t('hr.payroll.run.cta')}
              </Button>
            </div>
          </>
        )}
      </div>
    </DialogOverlay>
  )
}
