# 55. Employment-type payroll classification — pegawai tetap / bukan pegawai / pegawai tidak tetap

Date: 2026-08-10

## Status

Proposed

Extends [ADR 0031](0031-indonesian-statutory-payroll-official-datasets.md) (fulfils its deferred
"`EmploymentType != PERMANENT` should reject the run" gate); reuses the December rule-swap +
run-scope precedents from [ADR 0032](0032-payroll-liability-recognition.md) /
[ADR 0035](0035-thr-run-type.md).

## Context

The owner wants employee types **tetap / kontrak / freelance** to "adjust the salary composition".
Today `EmploymentType` (`PERMANENT | CONTRACT | INTERN | PROBATION`) is **stored but never read by the
payroll engine** — `GrossToNetCalculator` dispatches income tax purely on the linked
`StatutoryRule`'s `StatutoryCalcType`, so **everyone is computed as a *pegawai tetap*** (monthly TER +
December Art-17). Pointing a run at a daily worker or a freelancer today silently produces the wrong
PPh 21 — a latent compliance hole ADR 0031 already flagged.

Indonesian PPh 21 (UU HPP 7/2021; PP 58/2023; PMK 168/2023; PER-2/PJ/2024) classifies the **recipient**,
not the contract label, into three families with **different tax base, BPJS eligibility, and THR
eligibility**: *pegawai tetap*, *pegawai tidak tetap* (harian lepas), and *bukan pegawai*.

## Decision

1. **Payroll resolves a per-person TAX PROFILE from employment type; the calculator stays
   type-agnostic.** The profile is resolved in `PayrollRunWriter` (where `RunType`/legal-employer are
   already resolved), which selects which rule the person's `PPH21` component resolves against and which
   statutory components apply — mirroring the existing in-memory December `PPH21` rule-swap
   (`PayComponent.asAnnualTrueUpVariant`). New tax mechanisms are new `StatutoryCalcType` branches, not
   an `EmploymentType` switch inside the engine (preserves "exactly one income-tax family per run",
   HR-9 zero-figures-in-Java, and byte-identical re-runs).

2. **Type → tax profile mapping:**
   - **tetap → `PERMANENT`** = *pegawai tetap*. Unchanged (existing TER + Dec Art-17 path).
   - **kontrak → `CONTRACT` (PKWT)** = *pegawai tetap* for monthly pay — **byte-identical tax treatment
     to PERMANENT** (BPJS-mandatory, THR-eligible). This is the correct answer, not a simplification;
     PKWT differs only in non-tax lifecycle facts (fixed end date, end-of-contract *uang kompensasi* —
     out of scope here). Pinned by test.
   - **freelance → SPLIT into two** (the word maps to two statutorily-opposite realities, never one):
     - **`NON_EMPLOYEE`** = *bukan pegawai* (per-project contractor): **DPP = 50% × gross fee**, then
       Art-17 progressive; **no employer BPJS, no THR**; base = per-run fee; NPWP ×1.2 still applies; no
       biaya jabatan / PTKP (the 50% deemed cost replaces biaya jabatan).
     - **`DAILY_CASUAL`** = *pegawai tidak tetap* (harian lepas): daily-threshold / TER-Harian method;
       still an employee (BPJS/THR per PKWT). **Deferred** (needs a daily/hourly rate basis + a
       days-worked input).

3. **Engine changes (bukan-pegawai path):** add `StatutoryCalcType.DEEMED_NET_PROGRESSIVE`
   (params: `deemed_rate_bp` = 5000, reuse the Art-17 brackets + `no_npwp_surcharge_bp`) with a matching
   `GrossToNetCalculator` branch reusing `walkBrackets`/the surcharge; a new self-contained dataset
   `ID-2026.4.json` adding `PPH21_BUKAN_PEGAWAI`; the per-person rule-swap in `PayrollRunWriter`; and a
   BPJS/THR exclusion for `NON_EMPLOYEE`. **No `PayslipLine`/event/Avro/schema change, no column
   migration** (the enum is a Java-only value — `employment_type` is `VARCHAR(32)`, no CHECK). Contractor
   cost points at a **professional-fees GL** (finance chart row + `mapping_rule`, data-only, the V42
   `6110 Overtime` precedent) so it is not booked as labor.

4. **New invariant — one tax profile per (employee, period).** The engine computes statutory **once on
   the combined multi-assignment gross** (aggregate-then-allocate). Mixing a *pegawai tetap* assignment
   and a *bukan pegawai* assignment in one masa pajak is uncomputable; reject/split, alongside the
   existing single-legal-employer invariant.

5. **Scope gate FIRST (closes the hole):** a payroll run **rejects (422)** any employee whose type the
   engine does not yet correctly handle. Day one allows `PERMANENT` + `CONTRACT`; each further type
   opens only when its path lands. `INTERN`/`PROBATION` get an explicit decision (a monthly probationer
   is *pegawai tetap*; magang stipends may be *bukan pegawai*) rather than defaulting silently.

6. **Every new statutory figure ships `ILLUSTRATIVE_PLACEHOLDER`** (loud `uses_illustrative_rules`)
   until a human verifies it against current PMK 168/2023 + DJP. The 50% DPP is citable; the daily
   thresholds are NOT settled (the task mixed the pre-2024 Rp 450k/4.5jt regime with PMK-168 TER-Harian)
   and must be human-confirmed before any `DAILY_CASUAL` figure is `OFFICIAL`.

## Consequences

- Correct per-class withholding; the current silent-wrong-answer for non-permanent staff is closed by
  the gate. kontrak is provably identical to tetap. Freelancers get the bukan-pegawai method with no
  phantom BPJS/THR and correctly-classified GL cost.
- **Deferred:** `DAILY_CASUAL`; bukan-pegawai *berkesinambungan* (cumulative DPP + monthly PTKP); PKWT
  *uang kompensasi* + termination-month true-up; 1721-A1 vs 1721-VI *bukti potong* (Track P P9).
- **Compliance gate:** the flagged DJP/PMK figures must be human-verified before flipping to `OFFICIAL`;
  see the domain analysis' "uncertainties" list.

## Plan

- **P0** — the 422 scope gate (`PERMANENT`/`CONTRACT` only) + explicit `INTERN`/`PROBATION` handling.
- **P1** — allow `CONTRACT`; tests pinning byte-identical-to-`PERMANENT` monthly output.
- **P2** — `NON_EMPLOYEE` (bukan pegawai): `DEEMED_NET_PROGRESSIVE`, `PPH21_BUKAN_PEGAWAI` (`ID-2026.4`,
  illustrative-until-verified), per-person rule-swap, BPJS/THR exclusion, per-run fee input,
  professional-fees GL, the single-tax-profile invariant. The console HR contract form gains the type;
  the employee app shows it. ← the slice the owner is asking for.
- **P3 (defer)** — `DAILY_CASUAL` (rate basis + days-worked input + `DAILY_THRESHOLD`, verified figures).
- **P4 (defer)** — the long tail above.

Each phase MUST go through `domain-specialist` + `security-engineer`/`code-review` (money + statutory).

## Owner decision + grounded daily method (2026-08-10)

The owner's "freelance" = **daily / shift workers** (*pegawai tidak tetap* / harian lepas), so
**`DAILY_CASUAL` is the target** (promoted ahead of `NON_EMPLOYEE`, which is deferred). Sequence stays
P0 gate → P1 kontrak → then `DAILY_CASUAL`.

**Grounded method (PMK 168/2023, in force since 1 Jan 2024 — the current regime, superseding the
pre-2024 PER-16 Rp 4.5jt/month method):** monthly withholding for a daily worker uses **TER Harian**
on the daily wage — **≤ Rp 450,000/day → 0%**, **> Rp 450,000 to ≤ Rp 2,500,000/day → 0.5%**, and
**> Rp 2,500,000/day → Art-17 progressive on (50% × daily gross)**. `StatutoryCalcType.DAILY_THRESHOLD`
carries these as params (`day_zero_ceiling_minor`, `day_low_rate_bp`, `day_high_threshold_minor`, and
the Art-17 bracket + `deemed_rate_bp` reuse). **These figures ship `ILLUSTRATIVE_PLACEHOLDER` (loud
flag) until an accountant confirms them against the current PMK/DJP — never `OFFICIAL` on my say-so.**

**Days-worked capture (new gap):** there is no attendance/clock system (ADR 0033 = leave + overtime
only), so daily pay `= daily_rate × days_worked` has no source. **MVP = a manual per-run days-worked
entry** per casual employee (a payroll-run work input, alongside the existing overtime/unpaid-leave
inputs) + a `daily_rate` basis on the compensation package (`PayFrequency.DAILY`). A real attendance
feed is a later, separate concern.

**Build pieces (`DAILY_CASUAL`), each reviewed:** enum value; `PayFrequency.DAILY` + a
`compensation_package` rate-basis migration; a per-run days-worked input (entity + migration + HR entry
UI); `DAILY_THRESHOLD` calc type + `StatutoryParams` + `GrossToNetCalculator` branch; `ID-2026.4`
dataset (`PPH21_TER_HARIAN`, illustrative); `PayrollRunWriter` per-person profile + the P0 gate + the
one-tax-profile invariant; console HR contract-type + rate + days entry; employee-app display. This is
a multi-part program with a hard accountant-verification gate on the figures — not a single small change.
