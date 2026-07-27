# Native — Claude Code Build Plan

How to use this with Claude Code, then the task sequence. Each task is one focused session. The order is **validation-slice first** — ship the thinnest usable loop to your requesters before the full platform.

## How Claude Code should work
- **One task = one session = one commit.** Follow the loop in `CLAUDE.md`.
- **Read before building.** Always: `CLAUDE.md`, `ARCHITECTURE.md`, `docs/EVENT-CATALOG.md`.
- **Model:** Opus for planning/design and contracts; Sonnet for implementation; Haiku for Explore subagents.
- **You (human) own:** event-contract design, payroll/consolidation logic, currency/FX rules, and all security boundaries. Review every time. Delegate scaffolding, CRUD, endpoints, consumers, projections, tests, UI.
- **Definition of done** is at the bottom.

## Repo layout (monorepo)
```
/native
  /build-logic
  /service-template
  /libs
    /events            # Avro schemas + types, outbox + idempotency helpers
    /tenant            # RLS context, scoped-value tenant propagation, Auditable base
    /money             # Money type (minor units + ISO-4217 code), formatting, FX interface
    /entitlement-check
  /services
    /gateway /org-service /entitlement-service /employee-service
    /finance-service /notification-service
    /restaurant-service /carwash-service /laundromat-service
  /apps
    /console           # management console (Vite + React + TS + Tailwind + shadcn) — owner/manager
    /employee          # employee app (mobile-first PWA) — phase 2/3
    /ui                # shared design system + i18n locales (en, id)
  /docs                # ARCHITECTURE.md, EVENT-CATALOG.md, ADRs, saved plans
  /docker
  CLAUDE.md
```

## Phase 0 — Skeleton (lean; days)
Build only what the first slice needs. Defer Linkerd, Vault, full observability.

**M0.1 — Monorepo + Gradle conventions.** Gradle (Kotlin DSL) multi-module with shared convention plugins (Java 25 toolchain, Spring Boot 4 BOM, test, sonar). Acceptance: `./gradlew build` passes on the empty tree.

**M0.2 — Shared libs: tenant + events + money + Auditable.**
- Produce: `libs/tenant` (the `Auditable` `@MappedSuperclass` with audit columns + `company_id`, an `AuditorAware` reading the JWT, RLS session-context wiring, scoped-value tenant propagation); `libs/events` (Avro plumbing, transactional-outbox writer, idempotent-consumer helper); **`libs/money` — a Money value type = integer minor units + ISO-4217 currency code, arithmetic that refuses mixed-currency operations, and a formatting helper; never floats.**
- Acceptance: audit columns populate from a mocked security context; an outbox write + stubbed relay emit one event; RLS context sets/clears; Money rejects float construction and mixed-currency add, and formats correctly per currency.

**M0.3 — Service template.** Spring Boot 4 app, Postgres + Flyway baseline, depends on `libs/tenant` + `libs/events` + `libs/money`, OTel wired, `/healthz`, Dockerfile, a sample `Auditable` entity + migration. Acceptance: builds; `/healthz` 200; migration applies; sample entity round-trips with audit columns.

**M0.4 — Local dev stack + CI.** `docker/compose.dev.yml` (Postgres, Kafka + Schema Registry, Keycloak, Debezium); GitHub Actions running build + tests + `sonar` + image build (hard-gate sonar and dependency scan). Acceptance: stack comes up healthy; CI green on the template.

**M0.5 — Frontend foundation.** Scaffold `/apps/console` (Vite + React + TS + Tailwind + shadcn) and `/apps/ui` (the Native design tokens + **react-i18next with `en` and `id` locale files**). Acceptance: console builds and renders the shell; i18n is wired with language driven by config (not a toggle); design tokens applied.

## Phase 1 — First usable slice (THE VALIDATION MILESTONE)
Thinnest loop: **create company (set base currency + default language) → log in → record a sale → see consolidated revenue.** Deploy as **1–2 units**. Put it in front of your requesters. Minimal scope (ARCHITECTURE.md §6).

**M1.1 — Identity + gateway.** Keycloak realm/client with `company_id` + roles in the JWT; the `gateway` doing JWKS validation, context injection, Redis per-tenant rate limit. Acceptance: login yields the claims; unauthenticated rejected at gateway; valid routed with tenant context.

**M1.2 — org-service (minimal).**
- Objective: create one company with its **base currency + default language**, and one business.
- Produce: `company` (incl. `base_currency` [immutable] + `default_language`) + minimal `org_unit`; create-company + create-business endpoints; publish `CompanyCreated` (carrying `base_currency`, `default_language`) via the outbox; RLS on every table.
- Acceptance: creating a company persists `base_currency` + `default_language` and emits `CompanyCreated`; **`base_currency` cannot be changed via update once set**; cross-tenant read blocked (RLS test); contract test passes.

**M1.3 — Company onboarding wizard (frontend).**
- Objective: the creation flow where base currency + default language are set — **not** dashboard toggles.
- Produce: the Native onboarding screen — company name, **base currency (marked permanent, with a live `Intl` preview)**, default language, first business (name + type) — calling the org-service create endpoints.
- Acceptance: completing the wizard creates the company with the chosen currency/language + first business; the currency field communicates permanence; **no currency/language toggle exists in the dashboard.**

**M1.4 — restaurant-service (minimal).** A `sale` aggregate; record-sale endpoint; publish `SaleRecorded` via the outbox. Amounts are **Money** in the company base currency. Acceptance: recording a sale emits exactly one `SaleRecorded` (outbox + idempotency on retry); contract test passes.

**M1.5 — finance-service (minimal).** Consume `SaleRecorded` → append-only `ledger_posting` (**Money**, tenant + period dimensions) → consolidated-revenue read model + query API. Single base currency; **no FX yet.** Acceptance: a published `SaleRecorded` posts to the ledger and moves the read model; re-delivery doesn't double-count.

**M1.6 — console (minimal) + wire the loop.** The Native console — login, record-sale form, dashboard reading consolidated revenue. **i18n via react-i18next (en/id); currency and number formatting via `Intl` reading the company base currency; NO currency or language toggle** (values come from company config; per-user language from profile). Acceptance: end-to-end — create company → log in → record a sale → consolidated revenue updates; UI renders in the company default language and base currency; switching the user's profile language re-renders copy; figures format via `Intl`. Package as 1–2 deployables and deploy.

> **Stop and validate with real requesters before Phase 2.**

## Phase 2 — Expand on validated ground
- **entitlement-service** + the `entitlement-check` library; real gating in the verticals.
- **org-service (full)** — business unit, outlet, team (ADR 0012 — flat tree, no branch level), legal_employer; the company-as-keystone boundaries.
- **employee-service (records only)** — `employee`, `employment_contract`, `assignment`; publish `EmployeeChanged`/`AssignmentChanged`; verticals build a local staff read model (hydration: compacted topics + snapshot API).
- **A second vertical** (carwash) from the proven pattern.
- **finance-service** — mapping rules + the dimensional ledger proper; expenses.

## Phase 3 — HR depth, multi-currency, group consolidation
- **Payroll engine** — versioned `statutory_rule`, gross-to-net, `rule_version`, `gl_account`, `PayrollPosted` + `LaborCostAllocated`. Seed + verify current-year Indonesian statutory figures (DJP, BPJS). Completeness gate (`PeriodSealed`).
- **Compensation engine; mobility + approval sagas;** multi-outlet aggregate-then-allocate (same-`legal_employer` invariant).
- **Multi-currency** — finance-service `fx_rate` (effective-dated) + presentation-currency conversion (view books in another currency) + **currency translation for cross-currency consolidation** (closing rate for balances, average for P&L). Add a rate source.
- **Group consolidation** — related-party tagging, `consolidation_ledger`, elimination, close process (lock → match → flag → `ConsolidationClosed`).
- **Employee app** — the mobile-first PWA (schedule, payslip, leave, POS), localized + currency-aware.
- **Third vertical** (laundromat).
- **Full hardening** — mesh mTLS, Vault, field-level PII encryption, contract + chaos + idempotency suites, per-service backup + DR (RPO/RTO).

## Definition of done (every task)
- Unit + integration tests pass.
- `sonar` quality gate green.
- Contract tests pass for any event change (backward-compatible only).
- A fresh-context `/code-review` pass is clean.
- Money/tenancy/auth changes human-reviewed.
- Ships with observability (metrics, structured logs, `/healthz`). Deployable-and-instrumented, not just compiling.
- **No floats for money; no hardcoded user-facing strings; no dashboard currency/language toggle.**

## The order, in one line
```
M0 skeleton (incl. Money type + i18n base) → M1 usable slice (create company w/ currency+language → record sale → consolidated revenue) → VALIDATE
→ M2 entitlements, full org, employee records, 2nd vertical
→ M3 payroll, multi-currency/FX, group consolidation, employee app, hardening + service splits
```
