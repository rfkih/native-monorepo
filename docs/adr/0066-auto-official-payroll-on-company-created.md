# 0066. Auto-activate OFFICIAL payroll on `CompanyCreated` — no new tenant left illustrative

- **Status:** Accepted
- **Date:** 2026-08-17
- **Deciders:** rifki, Claude (Opus 4.8)
- **Related:** [0042](0042-go-live-official-rates.md) (OFFICIAL rates = go-live default),
  [0031](0031-canned-official-statutory-datasets.md) (canned versioned OFFICIAL datasets + activation),
  [0025](0025-odoo-signup-country-defaults.md) (country → base currency; ID → IDR),
  [0059](0059-english-first-localization.md) (multi-country; IDR as the Indonesia proxy),
  rule 3 (idempotent consumers), rule 5 (RLS), rule 7 (event catalog + contract test)

## Context

A production report: the amber **"Angka ilustratif"** badge stayed on payroll (and the derived
finance P&L) even though the app had "gone live" on OFFICIAL rates ([ADR 0042](0042-go-live-official-rates.md)).
Root cause, confirmed in code across three independent flag sources:

1. **Payroll** — `PayrollRunWriter.freezeRuleSet` stamps `payroll_run.uses_illustrative_rules` /
   `payslip_line.is_illustrative` ONCE at `calculate()` and never recomputes (HR-7 reproducibility:
   activating official rules must not rewrite an already-posted run).
2. **Finance** — the dashboard/Laba-Rugi badge is `bool_or(journal_entry.uses_illustrative_rules)`
   over the period on an immutable (`updatable=false`) ledger; once any illustrative posting lands in
   a period it stays badged there forever ([ADR 0065](0065-gl-derived-dashboard-pnl.md) made the
   dashboard GL-derived, so it inherits this).
3. **POS tax** — resolved `tax_charge_rule.provenance`; the OFFICIAL-flip migrations are demo-tenant
   only (separate concern, see "Out of scope").

For payroll the persistence is by design; the real defect is upstream: **activating the OFFICIAL
statutory dataset is a manual per-tenant runtime action** (the console setup-gate button →
`POST /api/v1/payroll-setup/seed-official-bootstrap`, [ADR 0031](0031-canned-official-statutory-datasets.md)).
A tenant that skipped it — or ran payroll before pressing it — stays on ILLUSTRATIVE_PLACEHOLDER
rules, and every run it posts is frozen illustrative (and taints that period's GL). There was **no
automatic activation on company creation**: entitlement-service consumes `CompanyCreated` to seed
default entitlements, but nothing seeded payroll.

## Decision

**employee-service consumes `CompanyCreated` and auto-activates the OFFICIAL statutory dataset for a
new tenant**, so no new company is ever left on illustrative payroll rules.

- **Reuses the go-live composition.** The consumer calls the existing idempotent
  `PayrollSetupService.seedOfficialBootstrap(baseCurrency, DEFAULT_OFFICIAL_DATASET_VERSION)` — the
  same one the console setup-gate calls. The default dataset (`ID-2026.1`) is now a single shared
  constant. No new seeding logic.
- **Indonesia-gated.** The dataset (BPJS / PTKP / PPh21 TER) is Indonesian statutory law and Native
  is multi-country ([ADR 0059](0059-english-first-localization.md)). Only a company whose
  `base_currency == IDR` — the established Indonesia proxy ([ADR 0025](0025-odoo-signup-country-defaults.md):
  country ID → IDR) — is auto-seeded; a non-IDR company is skipped (and, having no rules, correctly
  shows no badge). This is why employee-service's consumer view keeps `base_currency` where
  entitlement-service drops it.
- **Idempotent + tenant-scoped (rules 3, 5).** Raw Avro bytes via the shared `libs/contracts`
  schema; dedupe by event UUID (`ProcessedEventStore.processOnce`) in the same transaction as the
  seed; tenant bound from the event's `company_id` via `TenantContext.callAs` so RLS applies. Missing
  / non-UUID `id` header or a poison payload fails closed to `CompanyCreated.DLT`.
- **Forward-only by construction.** The listener runs in a **dedicated consumer group**
  (`employee-payroll-bootstrap`) pinned to `auto.offset.reset=latest`, isolated from the main
  `earliest` group. Enabling it therefore only auto-bootstraps companies created *after* it starts —
  it deliberately does NOT replay historical `CompanyCreated` and mass-activate every existing
  tenant at once (an implicit, hard-to-reverse bulk mutation that could also supersede a tenant's
  hand-verified statutory override).

## Consequences

- **New IDR tenants are OFFICIAL from creation** → their payroll runs are never flagged illustrative,
  and the derived finance P&L for those periods is clean. The badge stops appearing going forward.
- **Existing tenants are unaffected by this change** and must be activated deliberately (the "Part B"
  path): the console setup-gate button, or `POST /api/v1/payroll-setup/seed-official-bootstrap` per
  tenant. This is intentional — a per-tenant, owner-visible action, not a silent bulk write.
- **Two known forward-only miss windows** (both recoverable via the console setup-gate, neither
  currently alerted): a company created in the deploy window before the consumer establishes its
  `latest` position; and a company created while the consumer is down longer than the broker's
  offset retention. **Follow-up:** a periodic reconciliation that flags any IDR tenant with a
  pay-component catalog but no resolvable OFFICIAL statutory rule.
- **Not gated on the HR/payroll entitlement.** Every IDR company is seeded regardless of whether it
  has the payroll module. Deliberate: the seed is idempotent and cheap (unused catalog rows are
  harmless and RLS-scoped), and it avoids a race where payroll is used before an entitlement
  read-model propagates. Adding an entitlement gate would couple employee-service to entitlement
  state for no real benefit.
- **Historical data is left as audit truth** (owner's decision): already-posted illustrative runs
  and the periods they tainted keep their badge.

## Out of scope

- **POS tax badge (Fix 2).** Real tenants get no `tax_charge_rule` rows at all (the illustrative and
  OFFICIAL-flip migrations are hardcoded to the demo tenant `11111111-…`), so they charge zero tax
  and show no POS badge. Giving real tenants OFFICIAL tax provenance needs a per-tenant tax setup
  (the rate/regime is business-specific and SME-gated) — a separate decision, deferred.
- **Finance historical periods.** Clearing the badge on periods that already contain an illustrative
  posting would mean changing the flag's semantics or a ledger remediation — not done.
