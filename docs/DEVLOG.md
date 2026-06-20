# DEVLOG — history, key decisions, current status

> **For an AI agent:** this is the durable record of *what was built, why, and where we are* — the
> decisions especially (the code shows the *what*; this shows the *why*, which you can't re-derive).
> Keep it current: when you finish a milestone or make a design decision, add a dated line. The live
> task list is ephemeral; this file is the memory. Update the **Current status** section as you go.

## Current status (update me)
**Backend: complete, hardened, and proven end-to-end.** All of Phase 0–3 backend is built, every
milestone team-built → adversarially reviewed (code + security + domain-correctness) → fix-rounds →
full build green → committed. The validation slice runs **live** (sale → outbox → Debezium → Kafka →
finance → consolidated revenue = verified against real infra, not just Testcontainers). CI + Kustomize
deploy authored (unverified vs a real cluster). The whole codebase is **package-by-feature with
controller/service/repository/domain/dto/messaging layer sub-packages**, ArchUnit-enforced.

**Not done (hard gates — need a human/SME/infra, do NOT invent):**
- Frontends — **console slice live** (`frontend/console`: onboarding wizard, consolidated revenue/P&L
  dashboard, and a **cashier POS**, en/id, Intl money). Now behind **real Keycloak OIDC login** with
  **role-gated surfaces** (cashier → POS only; owner/manager → dashboard + POS), proven on the running
  authenticated stack. Remaining: the rest of the console (org tree, group consolidation, closes) +
  the employee PWA — design decisions, never autonomous.
- **Official DJP/BPJS statutory figures** — payroll ships `ILLUSTRATIVE_PLACEHOLDER` data (provenance
  column + loud seed + runtime flag); a tax SME must seed real effective-dated figures.
- **Full IAS-21 multi-currency consolidation** (CTA/OCI, historical-rate equity, opening-balance
  roll-forward) — ships a FLAGGED-SIMPLIFIED translation; needs an accounting SME.
- Live infra (a real registry/cluster/secrets for the deploy; a real notification transport).

**Open follow-ups (tracked):** notification real provider (needs a transport choice); payroll
expected-source registry (needs a rule); error-inbox/alerting **fleet rollout** beyond the finance
pilot + Grafana dashboards (ADR 0005; scorecard gap #11/#13). (DONE: the P3d deferred operational items —
`member_group_index` backfill, the within-company concurrent-close lock, the within-close MVC tests;
the finance-expansion posting-currency robustness guard; and the org-tree move/deactivate semantics —
cascade-deactivate + reactivation.)

## Key design decisions (the why)
- **Package root `id.co.nativeapp`** — `id.co` reverse-domain; `nativeapp` because `native` is a Java
  reserved word (illegal package segment).
- **Layered + package-by-feature, ArchUnit-enforced** (not hexagonal) — controller→service→repository→
  domain, grouped by capability; later refactored so each feature has explicit
  `controller/service/repository/domain/dto/messaging` sub-packages (user preference for readability).
  The gateway is the documented carve-out (reactive edge, not aggregate-bearing).
- **The `*Writer` pattern** — every `@Transactional` write is its own `@Component` (`*Writer`), so it is
  invoked through the Spring proxy: a self-invocation would bypass the tx advice AND the
  `RlsAutoApplyAspect` that sets the tenant GUC. Load-bearing for RLS. Services orchestrate, Writers
  transact, Readers query.
- **RLS is enforced, not assumed** — every service connects as its own non-superuser role; tables use
  `ENABLE`+`FORCE ROW LEVEL SECURITY`; the tenant is a Postgres GUC (`app.current_tenant`) set per
  transaction via a scoped value (not ThreadLocal). Tested as the non-superuser `app_user`.
- **Group consolidation cross-tenant model (P3d)** — a group is a SECOND RLS scope: a new
  `app.current_group` GUC + a CONJUNCTION policy `group_id = app.current_group AND company_id =
  app.current_tenant` on the group tables. Members PUBLISH their trial balances (TrialBalancePublished),
  finance never cross-tenant-reads. Adversarially verified bypass-free. **Decision:** single-reporting-
  currency consolidation is fully correct; multi-currency is FLAGGED-SIMPLIFIED (balance-check gate +
  residual to a flagged CTA reserve + `uses_simplified_translation_policy`); full IAS-21 deferred to an
  SME. (Memory: `p3d-consolidation-scope`.)
- **CDC wire = base64'd Avro bytes** — the outbox payload is `bytea` (raw Avro); Debezium decodes it as a
  `ByteBuffer` that `ByteArrayConverter` rejects, so the connector base64's it (`binary.handling.mode`)
  and the consumer's `libs/events Base64ByteArrayDeserializer` decodes it back. AvroSerde + the "no
  Confluent serde" design are unchanged. (See RUNBOOK gotcha #3.)
- **Flagged-illustrative domain data** — anywhere real domain law is needed but absent (statutory
  payroll figures, FX rates, consolidation policy), the MACHINERY is real but the DATA is loudly flagged
  (provenance enum + loud seed comment + a runtime flag on the run/event) so it can never be mistaken
  for verified production values. Never invent tax/accounting law as production values.

## Milestone history (newest first; commit refs are illustrative anchors)
- **Error-inbox + webhook alerting (finance pilot, ADR 0005)** — a DLT'd money event is no longer a
  silent failure. The Kafka DLT recoverer records each consume failure into a per-service
  `error_log` ops table (V14) via a fingerprint-deduped `INSERT … ON CONFLICT` upsert
  (`ErrorInboxWriter`, plain JdbcTemplate in a REQUIRES_NEW tx so it survives the rolled-back
  business tx), then fires an async webhook alert on occurrence-count milestones (1/10/100/every-1000;
  no-op when the URL is unset, so dev/CI never call out). Deliberate, ADR-recorded deviations:
  `error_log` is NOT Auditable and NOT RLS-scoped — it is cross-tenant **operator** data, `company_id`
  nullable context never an access key; the HR-6 mitigation in place of RLS is **PII redaction at
  write time** (email + ≥10-digit runs incl. space/hyphen-separated). Code-reviewed (money/tenancy
  gate) → fixed a **blocker**: the alert webhook had shipped the RAW exception message off-box; it now
  carries only the redacted message + the real dedup fingerprint, the upsert runs under a bounded tx
  timeout (no partition stall), and a payload test guards the egress. Closes scorecard **gap #11** for
  finance; fleet rollout + Grafana dashboards (#13) deferred to a follow-up ADR. 279 finance tests
  green. (commits `cd4d744` + `e51325c`)
- **OpenAPI docs — springdoc pilot (finance-service)** — finance now serves `/v3/api-docs`
  (OpenAPI 3.1) + `/swagger-ui`, generated from the live controllers. Pinned **springdoc-openapi
  3.0.x** — the Boot 4 / Framework 7 line; an earlier probe's 2.8.x (Boot 3) returns a Base64-mangled
  `/v3/api-docs` on Framework 7. An `OpenApiDocsSmokeTest` boots the service and asserts the endpoint
  is real OpenAPI JSON (not Base64) documenting the statements paths, so a Boot/springdoc bump that
  breaks doc generation fails the build. Docs sit behind the JWT chain and are not gateway-routed
  (dev/in-cluster only). Decision recorded in **ADR 0004**; fleet-wide rollout is a later ADR.
- **Financial Statements (Income Statement + Balance Sheet)** — finance gains a read-only statements
  API derived ENTIRELY from the double-entry GL (no new tables, no migrations). `GET
  /api/v1/statements/income?period=YYYY-MM` is period-scoped (REVENUE−EXPENSE=net, via
  `GlTrialBalanceReader`); `GET /api/v1/statements/balance-sheet?asOf=YYYY-MM` is CUMULATIVE — a new
  `JournalEntryRepository#glTrialBalanceAsOf` native query + `GlCumulativeTrialBalanceLineView`
  projection sums all journal activity where `period <= :asOf`. **Retained earnings is computed on
  read** (Σrevenue − Σexpense cumulative; never posted to the GL) so the sheet balances, with a
  defence-in-depth `assets == liabilities + equity` gate that throws on imbalance (an internal
  posting bug → 500). Sign conventions per `AccountType` (ASSET/EXPENSE debit-normal,
  LIABILITY/EQUITY/REVENUE credit-normal); all amounts `long` minor units + `Math.*Exact` (HR-8).
  Tenant-scoped via RLS only — readers are `@Transactional`, no manual `WHERE company_id`; a tenancy
  isolation test proves A's books are invisible to B. Code-reviewed → fixed: a multi-currency trial
  balance is now a TYPED `GlMultiCurrencyException` → **422** (was a bare `IllegalStateException` →
  opaque 500; mirrors the within-close `MultiCurrencyTrialBalanceException`), and an unmapped account
  a typed `GlUnmappedAccountException` (→ non-leaking internal 500, like `UnmappedLedgerAccountException`);
  added the missing `StatementsControllerTest` web-slice (200/204/400/422 + RFC-7807 shape) for parity
  with the sibling controllers. 248 finance tests green. The gateway routes + **owner/manager
  role-gates** `/api/v1/statements/**` → finance-service (a new `statementsRoute`, mirroring the
  pnl/revenue dashboard routes; a cashier is denied 403 at the edge), with a role-routing test. The
  **console** adds two owner/manager pages — Income statement (`/statements/income`) and Balance
  sheet (`/statements/balance-sheet`) — dashboard-style (KPI tiles + a bar chart + an expandable
  per-account breakdown), en/id, Intl money via the shared `money.ts`, reusing the illustrative
  badge; `tsc` + `vite build` green (also fixed a latent type error in the console's dev-proxy
  config). SME-deferred: SAK-EMKM presentation grouping/labels, comparative columns, Cash Flow
  statement.
- **Double-entry General Ledger** — finance gains a real double-entry GL (`journal_entry` + balanced
  `journal_line`, the invariant enforced in the aggregate so an unbalanced entry can't exist)
  ALONGSIDE the existing dimensional ledger (untouched). Every money event auto-posts a balanced
  journal in the SAME consume transaction via a data-driven posting-template framework — SME-gated:
  the Indonesian COA / account mappings / tax are higher-version effective-dated rows, with a loud
  flagged-illustrative seed today; the GL trial-balance read proves Σdebit==Σcredit. Code-reviewed →
  fixed (authoritative-period vs `periodOf(occurredAt)`, fail-loud on an unmapped account,
  illustrative-flag OR). V13. (commits `04fb971` + `ee3f400`)
- **Console production auth + POS** — real Keycloak OIDC (authorization-code + PKCE) login in the
  console; the SPA sends a bearer to the gateway, which now **role-gates** routes (cashier → the
  restaurant POS routes; owner/manager → the finance/org dashboard routes via a `RoleAuthorizationFilter`)
  and the SPA gates its surfaces by role (cashier → POS only). New restaurant **POS** (menu + atomic
  order checkout reusing `SaleRecorded`). Fixed a 100× IDR bug (`money.ts` fraction digits ≠ libs/money).
  Added org `GET /api/v1/companies/current`, a `native-console` PKCE realm client, a console Docker
  image + Kustomize overlay + CI job, and the **≥-blackheart standards scorecard** (a maintained
  competitive bar) to ENGINEERING-STANDARDS. Verified live end-to-end (owner sees dashboard+POS;
  cashier POS-only with the dashboard 403'd at the gateway; forged tenant headers stripped).
- **Org-tree move/deactivate semantics (#25)** — the undecided lifecycle semantics, resolved by user
  decision: deactivation **cascades** to the active subtree (one `DEACTIVATED` event per node), and a
  node can be **reactivated** (`REACTIVATED`, requires an active parent, no cascade down). Enforces a
  single invariant — *no active node may have an inactive ancestor* — across all four structural ops
  (cascade-deactivate; reactivate-needs-active-parent; can't move an active node under an inactive
  parent; can't create under an inactive parent). New `REACTIVATED` `change_kind` is a backward-compat
  Avro `string`; employee-service consumes via the `active` flag (no consumer change). Producer-only
  change, no migration. Adversarially reviewed (PASS; closed the create-guard gap it found); org +
  employee builds green.
- **Finance-expansion robustness — posting-currency guard (#26)** — a `SaleRecorded`/`ExpenseRecorded`
  in a currency other than the company's immutable base currency used to silently create a SECOND
  `consolidated_pnl`/`consolidated_revenue` row (keyed on `(company, period, currency)`), detonating
  later as a raw read-time `500` in `PnlReader`. Added a write-time guard (`PnlReadModelWriter
  .requireConsistentCurrency`, RLS-scoped) on the revenue+expense writers: a divergent posting throws
  a typed, non-retryable `MismatchedPostingCurrencyException` → the consume rolls back (no divergent
  row) and the record is DLT'd (fail-closed, money held, read stays clean). Mirrors the labor path's
  `CURRENCY_MISMATCH` guard + the within-close `MultiCurrencyTrialBalanceException`; the `PnlReader`
  multi-currency branch stays as a defense-in-depth backstop. Adversarially reviewed (PASS); full
  finance build green.
- **P3d deferred operational items (#46)** — (a) V12 backfills the non-RLS `member_group_index` from
  the FORCE-RLS `group_member` for pre-V10 memberships (whose `GroupMembershipChanged` was already
  deduped and will never re-fire, so a within-company close would silently emit no
  `TrialBalancePublished` for them); the migration uses a `NO FORCE`/`FORCE` bracket so the
  table-owning role can read across tenants at migrate time (a plain FORCE-RLS read sees zero rows
  with no GUC bound). (b) An advisory lock (`pg_advisory_xact_lock`, mirroring the labor primitive) on
  the within-company close serializes concurrent closes so the loser returns the idempotent no-op,
  not a UNIQUE-violation 500. (c) Web-slice MVC tests for `WithinCompanyCloseController` (200/422/400)
  + a concurrency regression test + a backfill projection test. Adversarially reviewed (PASS); full
  finance build green.
- **Live end-to-end validation + 3 CDC fixes** — ran the real outbox→Debezium→Kafka→finance loop;
  found+fixed the publication-mode, occurred_at-timestamp, and bytea/ByteBuffer (base64) bugs the
  stubbed-relay tests couldn't catch. Proven: `GET /api/v1/revenue` = the recorded sales.
- **Follow-up hardening sweeps** — consolidation money-math regression-locks; FX/mapping resolution
  determinism + a group-RLS migration-lint guard; P3d tenancy/predicate/typed-fault hardening; platform
  defense (show-details pinned, encoded-JSON PII guard, readiness composition, RLS-bean presence);
  payroll/labor guards (mixed-grain, top-bracket-cap, control-total currency, looped race).
- **CI + deploy (#24)** — GitHub Actions (build+test+image matrix) + Kustomize base+overlays + fixed the
  broken service Dockerfiles + the missing entitlement DB. Author-only / unverified vs a real cluster.
- **Layer-subpackage refactor** — every feature split into controller/service/repository/domain/dto/
  messaging; ArchUnit retargeted; service-template + docs updated. Pure move, 624 tests green.
- **Phase 3 (P3a–P3d)** — payroll engine (flagged statutory) · finance consumes labor cost (supersession,
  concurrency-safe) · FX/multi-currency (non-float FxRate, flagged stub) · group consolidation
  (two-GUC RLS, intercompany elimination, FX translation, the ConsolidationClosed producer — closing the
  loop notification consumes). 5 seams, each gated + fixed + committed.
- **#14 cross-cutting hardening** — JSON structured logs, readiness probes, RLS-ordering + anti-redeclare
  + float-ban ArchUnit guards.
- **Phase 2** — entitlement-service + the gate lib · full org tree + legal_employer · employee records
  (PII field-encryption) · carwash (2nd vertical) · finance expansion (mapping rules + dimensional
  ledger + expenses) · notification-service.
- **Phase 1 (validation slice)** — gateway + Keycloak (M1.1) · org-service create-company (M1.2) ·
  restaurant record-sale (M1.4) · finance consume → consolidated revenue (M1.5) · event transport (M0.4).
- **Phase 0** — Gradle monorepo + Java 25 toolchain · shared libs (money/tenant/events) · service-template
  · the quality gates · the engineering-standards + code-structure docs.
