# PROJECT-MAP — the orientation index (read this first)

> **For an AI agent:** read this file to orient before exploring. It is the dense map of *what
> exists and where*, so you can jump straight to the right module/file instead of globbing the tree.
> Companion docs: **RUNBOOK.md** (run/test/debug + gotchas), **DEVLOG.md** (history, decisions, current
> status), **EVENT-CATALOG.md** (event contracts), **CODE-STRUCTURE.md** (layering), **ARCHITECTURE.md**
> (the why). The 9 hard rules live in **/CLAUDE.md** — never violate them.

## What this is
**Native** — a multi-tenant B2B SaaS: per-company console over independently-deployable vertical
services + a shared platform layer + an event-driven financial consolidation core + an HR/payroll
module. Java 25 · Spring Boot 4.1 · Gradle (Kotlin DSL) · PostgreSQL 16 (DB-per-service) · Kafka +
Debezium CDC (transactional outbox) · Keycloak (OIDC) · Redis. Localized (en/id), multi-currency
(IDR/USD). Package root: **`id.co.nativeapp.<service>`**.

## Monorepo layout
```
build-logic/        4 Gradle convention plugins (native.java-conventions, .spring-conventions,
                    .spring-boot-app, .quality) — every module applies these, not raw config.
libs/               shared PLATFORM (auto-config, not deployable):
  money             Money (int minor units + ISO-4217, never float) + FxRate (scaled-int, no float)
  contracts         event Avro schemas (.avsc) — the SINGLE source of truth; producers + consumers
                    both depend on it, no per-service copies (ADR 0003). Classpath: avro/<Event>.avsc
  tenant            Auditable base entity, RLS GUC wiring, RlsAutoApplyAspect, scoped-value TenantContext
  events            transactional outbox writer, ProcessedEventStore (idempotency), AvroSerde (raw bytes),
                    Base64ByteArray(De)serializer (Kafka wire), StubRelay
  security          JWT/JWKS validation, tenant-from-token filter, RFC-7807 ApiExceptionHandler
  observability     shared JSON logback (logback-native-json.xml) + Kafka readiness health indicator
  entitlement-check cached "is company entitled to module X?" gate
service-template/   the blueprint every service is cloned from (widget feature + the ArchUnit suite)
services/           the 8 deployable Spring Boot apps (see table below)
docs/               this map, RUNBOOK, DEVLOG, ARCHITECTURE, EVENT-CATALOG, CODE-STRUCTURE, STANDARDS
  adr/              Architecture Decision Records — the append-only "why" log (read adr/README.md)
docker/             compose.dev.yml (Postgres/Kafka/SchemaReg/Debezium/Keycloak/Redis) + connector/realm/init
deploy/             Kustomize base + per-service overlays (#24, author-only-unverified)
.github/workflows/  ci.yml (build + test + image matrix)
.claude/            agents/ (the 10-agent team), commands/ (slash commands: /new-service /new-feature
                    /new-event /new-migration /native-check), settings.json (shared safe allowlist)
```

## Services (the 8 deployables)
| service | what it owns | produces (event) | consumes | DB migrations |
|---|---|---|---|---|
| **gateway** | the only external edge: JWKS-validates the JWT, injects `X-Company-Id/X-Actor/X-Roles`, Redis rate-limit. Reactive Spring Cloud Gateway, **no DB**. Packages: `security/filter/ratelimit/config` (not JPA layers). | — | — | — |
| **org-service** | company (immutable base_currency + default_language), org tree (unit/branch/outlet/legal_employer), consolidation_group + membership | CompanyCreated, OrgUnitCreated/Changed, GroupDefined, GroupMembershipChanged | — | V1–V3 |
| **restaurant-service** | 1st vertical: `sale` aggregate | SaleRecorded | — | V1 |
| **carwash-service** | 2nd vertical: `wash`, entitlement-gated; metrics | SaleRecorded, MetricPublished | EntitlementGranted/Revoked, EmployeeChanged, AssignmentChanged | V1 |
| **employee-service** | HR: employee/assignment (PII-encrypted) + **payroll engine** (gross-to-net, flagged-illustrative statutory) | EmployeeChanged, AssignmentChanged, PayrollPosted, LaborCostAllocated | OrgUnitCreated/Changed, MetricPublished, PeriodSealed | V1–V3 |
| **finance-service** | the consolidation core: dimensional ledger, P&L, FX, group consolidation. **The big one (V1–V11)** | ConsolidationClosed, TrialBalancePublished | SaleRecorded, ExpenseRecorded, PayrollPosted, LaborCostAllocated, GroupDefined, GroupMembershipChanged, TrialBalancePublished | V1–V11 |
| **entitlement-service** | module entitlements per company + billing | EntitlementGranted/Revoked | CompanyCreated | V1 |
| **notification-service** | notify + (stub) delivery | DeliveryReceipt | ConsolidationClosed | V1 |

Ports/creds for local run: see RUNBOOK. Each non-gateway service connects as its own non-superuser
Postgres role `<svc>_service` (so **RLS is enforced at runtime**).

## finance-service feature packages (the consolidation core, by area)
`revenue` (SaleRecorded→ledger+consolidated_revenue) · `expense` (ExpenseRecorded) · `pnl` (consolidated P&L) ·
`mapping` (chart_of_account + gl resolution) · `labor` (Payroll/LaborCost→labor cost, P3b) · `fx` (fx_rate +
translation + presentation, P3c) · `group` (group membership read model, P3d-1) · `grouptb` (group
trial-balance ingest + the two-GUC RLS, P3d-2) · `consolidation` (the group close engine + eliminations +
CTA + authz/read API, P3d-3/4) · `withinclose` (within-company close → balanced TrialBalancePublished +
ConsolidationClosed producer, P3d-4a).

## The end-to-end event flow (proven live — see DEVLOG)
```
org: create company (CompanyCreated, base currency immutable)
vertical: record sale  ──SaleRecorded──▶  finance: ledger_posting → consolidated_revenue
employee: payroll run  ──PayrollPosted / LaborCostAllocated──▶  finance: labor cost (EXPENSE)
finance: within-company close ──TrialBalancePublished──▶ finance: group_trial_balance (group-scoped RLS)
finance: group close (eliminate + FX-translate) ──ConsolidationClosed──▶ notification: notify
```
Transport: outbox table → Debezium (one connector per service DB) → Kafka topic == event_type → idempotent
`@KafkaListener` consumer (dedupe by the `id` header). Payload = base64'd Avro bytes (see RUNBOOK gotcha #3).

## Where do I find X
| I need… | go to |
|---|---|
| an event's schema / fields / producer / consumer | `docs/EVENT-CATALOG.md` + `services/<svc>/src/main/resources/avro/<Event>.avsc` |
| a service's DB schema | `services/<svc>/src/main/resources/db/migration/V*.sql` (immutable, append-only) |
| an HTTP endpoint | `services/<svc>/.../<feature>/controller/*Controller.java` (`@RequestMapping`) |
| a Kafka consumer | `services/<svc>/.../<feature>/messaging/*Listener.java` |
| the write/transaction logic for a feature | `…/<feature>/service/*Writer.java` (the `@Transactional` unit — RLS-bound) |
| an entity / value type / enum | `…/<feature>/domain/` |
| the money type / FX | `libs/money/.../Money.java`, `FxRate.java` |
| RLS / tenant / Auditable wiring | `libs/tenant/` (RlsAutoApplyAspect, TenantContext, RlsConnectionInitializer) |
| the outbox / idempotency / Avro serde | `libs/events/` |
| the layering rules (enforced) | each service `…/config/LayeredArchitectureTest.java`; doc in `CODE-STRUCTURE.md` |
| how to run / debug locally + gotchas | `docs/RUNBOOK.md` |
| what was built / why / current status | `docs/DEVLOG.md` |
| the 9 hard rules | `/CLAUDE.md` |

## Common tasks (the idiom — grep an existing example, mirror it)
- **Add a consumed event:** add the `.avsc` consumer copy → a `*Schema` (parse + AvroSerde) → a `*Listener`
  (idempotent via `ProcessedEventStore`, fail-closed to `<topic>.DLT`, tenant bound from the event) →
  add a consumer view to EVENT-CATALOG + a contract test. Mirror `finance/revenue/messaging/SaleRecorded*`.
- **Add a produced event:** write it via `OutboxWriter` in the SAME `@Transactional` unit as the state
  change; add the `.avsc` + an EVENT-CATALOG section + the contract triad. Mirror any `*Writer` that emits.
- **Add a table:** a new Flyway `V<n>__*.sql` (never edit an applied one), `extends Auditable` + `company_id`
  + `ENABLE`/`FORCE ROW LEVEL SECURITY` keyed to `current_setting('app.current_tenant')`. Money = BIGINT
  minor + CHAR(3). Mirror an existing migration.
- **Add a service:** clone `service-template` (inherits the layered ArchUnit + the platform auto-configs);
  add to `settings.gradle.kts`, the Postgres init (`docker/postgres/init`), a Dockerfile, a deploy overlay.
- **A money/tenancy/auth change:** run `/code-review` in a fresh context (mandatory).
