# 31. Indonesian statutory payroll — OFFICIAL canned datasets, the TER-transcription activation checklist, and PATCH-creates-new-row overrides

Date: 2026-08-02

## Status

Accepted

## Context

Track P phase P1 (commit 968c88f) landed the calculation MACHINERY for real Indonesian payroll —
`TER_TABLE` (PMK 168/2023 monthly effective-rate lookup), `ANNUAL_PROGRESSIVE` (UU HPP 7/2021
Art-17 December true-up), `HOURLY_RATE_TABLE` (PP 35/2021 overtime, dormant until phase P7), and
the `PERCENTAGE_CEILING` two-pass tax-base additions (`base_kind`, `employer_adds_to_tax_base`) —
while keeping HR-9 (zero statutory figures in Java) absolute: every number stays DATA in
`statutory_rule.params_json`. Phase P1 shipped no data for the new families; every tenant still
only has the `ILLUSTRATIVE-2026.1` catalog (`IllustrativeStatutorySeedWriter`, three rules:
`BPJS_KESEHATAN`, `PPH21_PROGRESSIVE`, `PTKP_RELIEF`).

Phase P2 must ship the actual FIGURES — but the task's own constraint is decisive: **the
implementing agent has no web access, and the PMK 168/2023 TER Lampiran is a ~44-band-per-category
table that must not be invented.** Every other figure the domain specification states with
citable confidence (UU HPP 7/2021 Art-17 brackets, PMK 101/2016 PTKP, PMK 250/2008 biaya jabatan,
Perpres 64/2020 / PP 44/45/46-2015 BPJS rates and caps) can ship as real, OFFICIAL data today. The
TER band table cannot be authored blind — pretending otherwise (e.g. inventing plausible-looking
bands and calling them OFFICIAL) would be the single worst thing this phase could do: silently
wrong take-home pay, dressed up as verified.

`RuleProvenance` (V2 migration) only allows `ILLUSTRATIVE_PLACEHOLDER | OFFICIAL` (a DB CHECK) —
there is no `OFFICIAL_PENDING_TRANSCRIPTION` third state, and adding one is out of scope for a
worker-launched phase (it touches a CHECK constraint another agent might be mid-migration against,
and the whole point of the two-value enum is "a placeholder can never be silently treated as
official" — a third state would need its own careful definition of which loud signals apply to
it).

**Same-day update.** The implementing agent shipped `PPH21_TER` as `ILLUSTRATIVE_PLACEHOLDER`
with only the anchor bands (per the constraint above), flagging the dataset's activation checklist
as the path to close the gap. Before this ADR was committed, the coordinating agent — with web
access — fetched the COMPLETE PMK 168/2023 TER band tables (44 bands for category A, 40 for B, 41
for C) from the official Lampiran (PP 58/2023) and cross-verified them against a second
independent source, with every spot-checked row matching between sources. That transcription
replaced the placeholder mid-bands in `ID-2026.1.json` in the same phase, before first commit —
`PPH21_TER` now ships `OFFICIAL` like every other rule in the dataset. This is the activation
checklist working exactly as designed, just compressed into the same session rather than a later
PATCH: a human (here, the coordinating agent, acting as the verifier) supplied primary-source
figures the implementing agent structurally could not.

## Decision

1. **A canned, versioned, classpath JSON dataset** —
   `statutory-datasets/ID-2026.1.json` (`OfficialStatutoryDataset`, pure JDK + Jackson, mirroring
   `StatutoryParams`'s zero-Spring precedent) — carries an array of rule objects
   (`rule_key`/`rule_version`/`calc_type`/`provenance`/`source_note`/`effective_from`/`params`)
   plus a component-catalog section (new `pay_component` rows to upsert). **Every rule's `params`
   is validated through its own `StatutoryCalcType` family parser the moment the dataset loads**
   (`StatutoryParams.validate`, new in this phase) — a transcription error fails a build-time test,
   never a running payroll. `OfficialStatutorySeedWriter.seed(datasetVersion)` is the runtime
   activator: same per-tenant `@Transactional`-writer idiom as `IllustrativeStatutorySeedWriter`
   (every payroll table is FORCE RLS; a plain Flyway `INSERT` cannot pass `WITH CHECK` with no
   tenant GUC bound) — **no migration**, mirroring the V3-no-op precedent.
2. **All ten rules ship OFFICIAL — every figure ID-2026.1 carries is real, cited data:**

   | rule_key | provenance | citation |
   |---|---|---|
   | `PPH21_TER` | OFFICIAL | PMK 168/2023 Lampiran (PP 58/2023) — the full 44/40/41-band-per-category (A/B/C) table transcribed + cross-verified against two secondary sources 2026-08-02; spot-check against the official PDF recommended at go-live (see the checklist) |
   | `PPH21_ARTICLE17` | OFFICIAL | UU HPP 7/2021 Pasal 17 + PMK 250/2008 (biaya jabatan fields) |
   | `PTKP_RELIEF` | OFFICIAL | PMK 101/2016 |
   | `BIAYA_JABATAN` | OFFICIAL | PMK 250/2008 (documentation-only — see §4) |
   | `BPJS_KESEHATAN` | OFFICIAL | Perpres 64/2020 |
   | `BPJS_JHT` | OFFICIAL | PP 46/2015 |
   | `BPJS_JP` | OFFICIAL | PP 45/2015 (⚠ ceiling adjusted every March — see the checklist) |
   | `BPJS_JKK` | OFFICIAL | PP 44/2015 (default risk class II only — see the checklist) |
   | `BPJS_JKM` | OFFICIAL | PP 44/2015 |
   | `OVERTIME_HOURLY` | OFFICIAL | PP 35/2021 (dormant — no component references it until phase P7) |

   This means a company that activates `ID-2026.1` gets `uses_illustrative_rules = false` on every
   run immediately — no rule in the shipped dataset is a placeholder. The `RuleProvenance` /
   `uses_illustrative_rules` machinery stays exactly as important going forward: a FUTURE dataset
   revision, or a company that never activates the official dataset at all (staying on
   `ILLUSTRATIVE-2026.1`), still needs the loud flag, and `BPJS_JP`'s ceiling / `BPJS_JKK`'s risk
   class are standing re-verification needs even for an all-OFFICIAL dataset (see the checklist).
3. **`OfficialStatutorySeedWriter` closes/deactivates overlapping rows, never edits in place.**
   For a `rule_key` already carrying an open row (e.g. the illustrative `BPJS_KESEHATAN` /
   `PTKP_RELIEF` / `PPH21_PROGRESSIVE`, whose `BPJS_KESEHATAN`/`PTKP_RELIEF` keys the official
   dataset reuses — `PPH21_PROGRESSIVE` has no official counterpart key and is left orphaned-but-
   active, harmless since no `pay_component` references it once `PPH21` rewires to `PPH21_TER`),
   the writer supersedes it
   (`StatutoryRule#supersede`) before inserting the new one — mirroring how
   `PayrollRunWriter.resolveStatutoryRules` throws on an ambiguous overlap, this is the write-side
   guard that prevents one. **Edge case: same-day activation.** The illustrative and official rows
   share `effective_from = 2026-01-01`; closing the prior row to `effective_from − 1 day` would
   produce `effective_to < effective_from` (invalid) or, if closed to the same day, a same-day
   double-resolution the run-time resolver rejects. `supersede` instead **deactivates**
   (`active = false`) whenever closing would not leave a valid, non-overlapping range — never a
   delete, and the row stays on file for audit. `PayComponent#applyCatalog` is the analogous
   upsert for the component catalog: it is how `PPH21` rewires from `PPH21_PROGRESSIVE` to
   `PPH21_TER` without a migration (insert-if-absent, else align every field, returning whether
   anything changed — the idempotent-reseed proof).
4. **`BIAYA_JABATAN` and `OVERTIME_HOURLY` ship as their own dataset rows without a linking
   `pay_component`.** Biaya jabatan's figures are consumed INLINE by `PPH21_ARTICLE17`'s
   `occupational_cost_bp`/`occupational_cost_cap_annual_minor` (the December true-up, phase P3);
   `OVERTIME_HOURLY`'s `HOURLY_RATE_TABLE` shape is parsed/validated but dormant until phase P7
   wires the OVERTIME earning component. Both ship now, OFFICIAL, so the regulation's figure has
   an independently-citable, auditable row the moment it's needed — not invented at the point of
   use. `resolveStatutoryRules` resolves every active rule regardless of whether a component
   references it, so an unreferenced row is inert (frozen into the rule-version set, never walked
   by the calculator) — verified by `GrossToNetOfficialDatasetTest`'s "biaya-jabatan-free" assertion.
5. **Setup API on the existing `/api/v1/payroll-setup/**` route** (already gateway-routed,
   DASHBOARD_ROLES): `GET /rules` (a native+projection list — `rule_key`/`rule_version`/
   `calc_type`/`provenance`/`effective_from`/`effective_to`/`source_note`/`active`, no `params`
   blob), `GET /rules/{ruleKey}` (the currently-open row's full detail, including `params_json` —
   404 if never seeded), `POST /seed-official {datasetVersion}` (404 unknown version; 409 if the
   tenant has no statutory rule on file yet — the seed inherits currency from an already-seeded
   rule rather than accepting one on the request, since `seed-illustrative` is always the
   bootstrap step in practice and the console never renders Setup before that; 200 + the
   inserted/closed/skipped/updated summary, idempotent), `PATCH /rules/{ruleKey}` (see §6). No
   `Idempotency-Key` header on any of these — they mirror `seed-illustrative`'s existing precedent
   (config-plane writes, not money movement; naturally idempotent by construction).
6. **PATCH creates a NEW effective-dated row — the human-verification / TER-activation path.**
   `StatutoryRuleOverrideWriter.override(ruleKey, paramsJson, effectiveFrom, sourceNote,
   provenance)` supersedes the rule_key's currently-open row and inserts a fresh one
   (`ruleVersion = oldVersion + "-OVERRIDE-" + effectiveFrom`, same calc family as the row being
   replaced — an override tunes figures/date/provenance, never the algorithm family). `paramsJson`
   is validated through the SAME `StatutoryParams` family parser the dataset loader uses, before
   anything is written. `effectiveFrom` must be strictly after the current row's `effectiveFrom`
   (→ 400 otherwise). This is the general human-verification mechanism the checklist below relies
   on — it is how `PPH21_TER` was carried from `ILLUSTRATIVE_PLACEHOLDER` to `OFFICIAL` in this
   same phase (a human/coordinating-agent transcribed the Lampiran verbatim and cross-verified it),
   and it is the SAME path a standing re-verification uses afterward (e.g. `BPJS_JP`'s ceiling,
   adjusted every March — OFFICIAL-to-OFFICIAL, a correction, never a provenance flip in that
   case). Never an in-place edit — a run that already froze the OLD version stays byte-identically
   reproducible (HR-7).

## The activation checklist (who verifies what, now and going forward)

- **`PPH21_TER`** — DONE for `ID-2026.1`: the full 44/40/41-band-per-category (A/B/C) PMK
  168/2023 Lampiran table was transcribed and cross-verified against two independent secondary sources (klikpajak.id/blog/pajak-penghasilan-pasal-21-2 full tables; akuntansimandiri.com/2026/05/cara-hitung-pph-21-ter.html spot rows) and the anchors of the reconciled domain spec
  sources 2026-08-02 (every spot-checked row matched between sources), then loaded verbatim into
  `ID-2026.1.json` and validated through `StatutoryParams.terTable` at classpath-load time
  (`OfficialStatutoryDatasetTest`'s drift guard pins the band COUNTS — 44/40/41 — plus three
  cross-verified spot rows, so a future edit that silently drops/reorders/mistypes a row fails a
  build-time test). **Recommended before go-live:** spot-check the loaded table against the
  official PDF — PMK 168/2023 Tentang PPh Pasal 21 TER
  (https://pajak.go.id/sites/default/files/2024-02/PMK%20168%20Tahun%202023%20Tentang%20PPh%20Pasal%2021%20TER.pdf)
  — as a third, primary-source confirmation. A future PMK amendment repeats this same checklist
  entry (transcribe, cross-verify against ≥2 sources, drift-guard the counts + spot rows, `PATCH
  /rules/PPH21_TER` with a fresh `rule_version`/`sourceNote`).
- **`BPJS_JP`'s ceiling (`ceiling_minor`)** — BPJS Ketenagakerjaan adjusts this figure EVERY MARCH
  by public announcement. Before/at activation in any given year, verify the current ceiling
  against the latest announcement and `PATCH /rules/BPJS_JP` if it has changed (the shipped
  `10,547,400` is this dataset's authoring-time figure, not a standing guarantee).
- **`BPJS_JKK`'s risk class** — this dataset seeds every tenant at the DEFAULT class II rate
  (0.54%) regardless of the company's REAL registered risk class (I–V, 0.24%–1.74%). A per-company
  risk-class attribute plus a dedicated calc family to select among the five rates is a documented
  follow-up (no `RISK_CLASS_PERCENTAGE` `StatutoryCalcType` exists yet — this phase deliberately
  did not invent one; `BPJS_JKK` ships as a plain `PERCENTAGE_CEILING` at the class-II rate,
  overridable via PATCH per tenant once the real class is known).
- **Every other row** (`PPH21_ARTICLE17`, `PTKP_RELIEF`, `BIAYA_JABATAN`, `BPJS_KESEHATAN`,
  `BPJS_JHT`, `BPJS_JKM`, `OVERTIME_HOURLY`) — re-verify only if the underlying regulation is
  amended; no standing per-year check like JP's is known to apply.

## Consequences

- A company can activate `ID-2026.1` today and get REAL BPJS legs, REAL PTKP, a REAL Art-17
  December-truth-up formula (once phase P3 wires the December branch), and now a REAL PPh 21 TER
  table too — every rule in the dataset is load-bearing, cited, OFFICIAL data. The number every
  payslip actually shows every month (PPh 21 withheld) is no longer a placeholder.
- `uses_illustrative_rules` (the three-layer flag: `statutory_rule.provenance` →
  `payroll_run.uses_illustrative_rules` → `payslip_line.is_illustrative`) now reads `false` for a
  run against `ID-2026.1` — the machinery itself is unchanged and still load-bearing for (a) a
  tenant that stays on `ILLUSTRATIVE-2026.1` and never activates the official dataset, and (b) any
  future dataset revision that ships a rule ahead of its verification (the same posture `PPH21_TER`
  briefly held within this phase, before the coordinating agent's cross-verified transcription
  landed pre-commit). The activation checklist — not the provenance enum — is now the durable
  artifact recording exactly what was verified, against how many sources, and when.
- The band-table drift guard (`OfficialStatutoryDatasetTest`'s count + spot-row assertions) is the
  concrete enforcement that stands in for "don't silently regress a transcribed table" — anyone
  editing `ID-2026.1.json`'s `PPH21_TER` bands in the future trips a build-time failure unless the
  counts and the three cross-verified rows still hold, which is deliberately cheaper than re-fetching
  and re-diffing the full 44/40/41-row tables on every change.
- `StatutoryRule#supersede`'s same-day-deactivate branch is a genuine new edge case future dataset
  authors must respect: shipping a NEW dataset version with the SAME `effective_from` as an
  existing open row is fine (it deactivates cleanly), but two dataset rows for the SAME `rule_key`
  in the SAME dataset file with different `effective_from` values is not validated against —
  `OfficialStatutoryDataset` assumes one row per `rule_key` per dataset (true of `ID-2026.1`);
  a future multi-effective-date dataset would need its own ordering pass.
- `GET /rules` now returns EVERY historical row (including deactivated same-day-superseded ones,
  which still carry the `9999-12-31` open-ended sentinel) with an `active` flag rather than
  filtering to "current" server-side — the console must not treat "open-ended" alone as "current"
  (mirrored by the `active` badge/dim treatment in `PayrollSetupTab`). A future phase could add a
  server-side "current only" filter if this proves confusing in practice.
- The JKK risk-class-selection gap and the December true-up's actual consumption of
  `PPH21_ARTICLE17`/`BIAYA_JABATAN` (phase P3) are the two most consequential tracked follow-ups
  from this phase — both already called out at their exact insertion point above rather than
  buried in a changelog.
