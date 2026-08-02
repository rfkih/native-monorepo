# 35. THR run type — the off-cycle Tunjangan Hari Raya allowance

Date: 2026-08-02

## Status

Accepted

## Context

Permenaker 6/2016 requires every employer to pay Tunjangan Hari Raya (THR) — a religious-holiday
allowance — no later than H-7 (seven days before the holiday): 1x monthly wage for an employee with
≥12 months' continuous service, prorated `months_of_service / 12` for an employee with ≥1 month but
<12 months, and nothing for <1 month. THR is off-cycle (paid before a holiday, not on the monthly pay
date) and, statutorily, carries ONLY the THR earning plus PPh21 — BPJS never applies to a THR
payment, and THR replaces (does not add to) base pay. `payroll_run` was keyed `(company_id, period,
run_seq)` — a single run_seq series per period — so a THR run for the same period as the ordinary
monthly run had nowhere of its own to go: either it would collide with the REGULAR run's counter, or
(worse) a `run_seq` correction to one type would numerically "supersede" the other type's still-valid
run under the December Art-17 true-up's `ACTIVE_RUN_PREDICATE`, silently dropping a whole run's
payslip lines out of the annual tax base (the landmine `PayslipLineRepository` had carried, flagged,
since the P3 review). PMK 168/2023's TER method also has a real interplay here: TER applies to a
*masa pajak*'s (month's) TOTAL gross bruto, so a THR run and a REGULAR run landing in the SAME period
must jointly withhold as if they were one combined payment, not two independently-taxed ones.

## Decision

1. **`payroll_run.run_type`** (migration V14): `REGULAR` (default) or `THR`, `CHECK`-constrained. The
   existing `UNIQUE (company_id, period, run_seq)` is dropped and replaced with
   `UNIQUE (company_id, period, run_type, run_seq)` — a THR run and a REGULAR run for the same period
   now get their OWN independent `run_seq` counters (`PayrollRunWriter#nextRunSeq` keyed
   accordingly) and coexist without superseding each other. No backfill guard is needed: every
   existing row is already `run_type = 'REGULAR'`, so the old 3-column key and the new 4-column key
   hold the exact same set of distinct tuples for pre-P8 data.
2. **The `ACTIVE_RUN_PREDICATE` fix (the P3-review landmine, step ONE of this phase, before anything
   else).** `PayslipLineRepository.ACTIVE_RUN_PREDICATE`'s correlated `MAX(run_seq)` subselect and
   outer join predicate are now scoped `AND pr.run_type = pr2.run_type` (both queries that reference
   it — `findActiveLinesForEmployeeYear`, `findActivePriorPeriodsForEmployeeYear` — inherit the fix
   automatically, a single `interface`-constant). This makes "active" per-`(period, run_type)`
   structural: a REGULAR run and a THR run for the same period are BOTH active simultaneously (each
   supersedes only its own type's lower `run_seq`), so BOTH correctly contribute to the December
   Art-17 annual base — exactly mirroring finance's own `PayrollRunLedgerRepository` queries, which
   were already scoped this way since ADR 0032 (Track P phase P4) landed the field ahead of this
   phase.
3. **`pay_component.run_scope`** (`ALL` default | `REGULAR` | `THR`): which run type(s) a component
   resolves on (`PayComponent#appliesTo(RunType)`). The engine filters
   `activeStatutoryComponents()` by this before every run. Dataset `ID-2026.3` (self-contained, like
   `ID-2026.2` — re-declares every byte-identical rule so a tenant can activate it directly from
   `ID-2026.1` without an orphan-sweep hazard) upserts: BASE, every allowance/commission/overtime/
   unpaid-leave/expense-reimbursement earning, and EVERY BPJS leg → `REGULAR` (BPJS statutorily NEVER
   applies to THR); PPH21 stays `ALL` (income tax applies to every run type); a NEW `THR` earning
   component (`WORK_INPUT_DERIVED`, taxable, GL `5150-THR`) → `THR`. `PayrollRunWriter` additionally
   fails loudly (`ThrRunMisconfiguredException`, 422) if a THR run is attempted while BASE's
   `run_scope` is still `ALL` (would double-pay a full month's base salary on top of the THR earning
   in the SAME run) or the `THR` component is not yet seeded — the single highest-stakes THR
   money-safety guard, never a silent double-pay or a silently-skipped earning.
4. **The THR earning substitutes for BASE, not a new calculator branch.** `GrossToNetCalculator`
   always emits exactly ONE unconditional line for `(PersonInput.baseComponent, PersonInput.basePay)`
   — for a THR run, `PayrollRunWriter#resolveThrPersonInput` sets these to the THR component and the
   prorated amount (`base x min(serviceMonths, 12) / 12`, `Money#mulDiv`) instead of BASE/base pay,
   with `earnings`/`otherDeductions` empty (THR carries ONLY the THR earning + PPh21) and
   `statutoryComponents` the SAME run_scope-filtered list every run now resolves. Zero calculator
   changes for the base-slot substitution — it is structurally exactly what the calculator already
   does. `serviceMonths` is complete months between `employee.hire_date` (new nullable column,
   migration V14, NOT PII) and the run period's end date, floored at zero; `hire_date` falls back to
   the employee's earliest assignment `effective_from` when absent, and fails loudly
   (`MissingHireDateException`, 422) if neither exists — silently defaulting to zero months would
   silently deny a genuinely-tenured employee their statutory THR.
5. **The combined-gross TER interplay** (`PayrollInputs.SiblingPeriodContext`,
   `PayrollRunWriter#siblingPeriodContext`, `GrossToNetCalculator`'s TER_TABLE branch): for EVERY
   run (REGULAR or THR), the writer looks up the employee's ALREADY-ACTIVE payslip lines from the
   OTHER run type for the SAME period (`PayslipLineRepository
   #findActiveLinesForEmployeePeriodAndRunType`, reusing the fixed `ACTIVE_RUN_PREDICATE`) and folds
   their gross bruto / withheld PPh21 into this run's TER computation: `rate =
   TER_rate(thisGross + siblingGross)`; `thisWithholding = rate x (thisGross + siblingGross) −
   siblingWithheld`. Null/zero sibling context (the overwhelming common case, and every pre-P8 run)
   is a byte-identical no-op. This is mathematically ORDER-INVARIANT — whichever of a THR/REGULAR
   pair computes SECOND nets the pair's TOTAL withheld to `TER_rate(combinedGross) x combinedGross`
   regardless of which one ran first (proved algebraically here, verified both orders by test).
6. **Documented residual: the December Art-17 true-up does not yet compose with a same-period THR
   sibling.** `buildAnnualContext`'s whole-fiscal-year assembly is a separate mechanism from the
   sibling-period lookup above; a REGULAR December run does not fold a same-period THR run's
   gross/withheld into its annual reconciliation. `PayrollRunWriter#calculate` WARNs loudly when it
   detects this composition (an active THR run exists for the same period as a December REGULAR run)
   rather than silently mis-reconciling — a tracked follow-up, mirroring the termination-month
   true-up residual already accepted in ADR 0031.
7. **Finance needs NO changes.** `run_type` landed on `PayrollPosted`/`LaborCostAllocated`/
   `PayrollLiabilitiesPosted` and `payroll_run_ledger`'s supersession queries were already scoped
   `(period, run_type)` in ADR 0032 (Track P phase P4), ahead of any THR run existing — this phase
   is finance's proof, not its build: a THR run and a REGULAR run for the same period post
   independent, non-superseding liability entries.
8. **Console**: a "Run THR" action beside the regular Run button (period + an informational
   holiday-date picker — NOT persisted, no schema column, logged only for the audit trail, does not
   drive proration); a `run_type` badge on run-history rows; a client-side run-type filter;
   `EmployeeDetailDrawer` gains an editable hire-date field.

## Consequences

- A THR run and a REGULAR run for the same period are two fully independent, non-superseding rows —
  an owner can run either first, correct either independently, and the combined-gross TER interplay
  still reconciles to the correct total withheld regardless of order.
- The `ThrRunMisconfiguredException`/`MissingHireDateException` fail-loud guards mean a THR run
  either computes correctly or refuses to run at all — there is no code path that silently
  double-pays base salary, silently omits the THR earning, or silently under-taxes a combined month.
- **Deferred (tracked, not built here):** the December Art-17 true-up's composition with a
  same-period THR sibling (WARNed, not solved); a per-company religious-holiday calendar (the
  holiday-date is caller-asserted, informational only); THR for non-monthly/non-permanent employees
  (out of the engine's existing pegawai-tetap-monthly scope, unchanged by this phase); statutory
  THR-specific reporting (bukti potong / SPT annotations distinguishing a THR-derived withholding) —
  deferred alongside the other statutory-outputs work (Track P Phase P9).
