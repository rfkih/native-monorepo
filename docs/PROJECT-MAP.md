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
Debezium CDC (transactional outbox) · Keycloak (OIDC) · Redis · MinIO (S3-compatible object store
for binary media — ADR 0048). Localized (en/id), multi-currency (IDR/USD). Package root:
**`id.co.nativeapp.<service>`**.

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
  media-storage     generic S3 client for the MinIO object store (ADR 0048): MediaStorage port,
                    {service}/{companyId}/{domain}/{sha256}.{ext} key builder, the fleet's ONE
                    magic-byte image validator, auto-config from native.media.*
service-template/   the blueprint every service is cloned from (widget feature + the ArchUnit suite)
services/           the 8 deployable Spring Boot apps (see table below)
frontend/console/   the per-company management console (Vite+React+TS+Tailwind+TanStack Query+i18n;
                    onboarding wizard + consolidated dashboard). Conventions: docs/FRONTEND-STRUCTURE.md
docs/               this map, RUNBOOK, DEVLOG, ARCHITECTURE, EVENT-CATALOG, CODE-STRUCTURE, STANDARDS
  adr/              Architecture Decision Records — the append-only "why" log (read adr/README.md)
  generated/        machine-readable manifests (services.yaml, events.yaml) — generated + drift-checked
                    (./gradlew generateProjectDocs / verifyProjectDocs; do not hand-edit)
docker/             compose.dev.yml (Postgres/Kafka/SchemaReg/Debezium/Keycloak/Redis/MinIO) +
                    connector/realm/init + minio/init.sh (bucket + prefix-scoped users, ADR 0048)
deploy/             Kustomize base + per-service overlays (#24, author-only-unverified)
.github/workflows/  ci.yml (build + test + image matrix)
.claude/            agents/ (the 10-agent team), commands/ (slash commands: /new-service /new-feature
                    /new-event /new-migration /native-check), settings.json (shared safe allowlist)
```

## Services (the 10 deployables)
| service | what it owns | produces (event) | consumes | DB migrations |
|---|---|---|---|---|
| **gateway** | the only external edge: JWKS-validates the JWT, injects `X-Company-Id/X-Actor/X-Roles`, Redis rate-limit. Reactive Spring Cloud Gateway, **no DB**. Packages: `security/filter/ratelimit/config` (not JPA layers). | — | — | — |
| **org-service** | company (immutable base_currency + default_language), org tree (unit/outlet/team/legal_employer, ADR 0012; business units carry an immutable LOWERCASE `vertical`: restaurant \| carwash \| barbershop), user-outlet assignments, consolidation_group + membership, **ADR 0049 P3a per-outlet device (kiosk) credential** — `device_credential` (AES-256-GCM password, own `PiiCipher`) + `POST/GET/DELETE /api/v1/org-units/{outletId}/device-credential` + `.../reset` (DASHBOARD_ROLES, owner/manager only): mints a Keycloak `cashier`/`actor_type=device` login bound to exactly one outlet via the existing `user_outlet_assignment` writer; excluded from the Team list | CompanyCreated, OrgUnitCreated/Changed, UserOutletAssignmentChanged, GroupDefined, GroupMembershipChanged | — | V1–V11 |
| **restaurant-service** | 1st vertical: `sale` aggregate + full restaurant POS backend (menu w/ per-item stock = the 86 gate — menu images live in the OBJECT STORE since ADR 0048: convert-on-write to `image_key`, public `/api/media/…` URLs, owner backfill `POST /api/v1/menu/images/migrate`; orders/bills/tables, register sessions ADR 0036/0038, menu-item stocktake, **ingredient inventory + ingredient stock opname ADR 0046** — `inventory` feature: `ingredient`/`ingredient_stocktake(_line)` tables, `/api/v1/ingredients/**` + `/api/v1/ingredient-stocktakes/**`; **ADR 0049 P0–P4 — `sale.sold_by_user_id` seller wire, `OperatorRequiredGuard` device-sale enforcement (`SaleWriter#create`/`#recordInCurrentTx`, `PaymentWriter#recordPendingDigitalInCurrentTx`), and `payment.sold_by_user_id` async operator threading (`PaymentCaptureWriter#capture`)**; recipes/BOM ADR 0050; **ADR 0067 Phase B — `goods_receipt`** table, V43: the priced-goods-receipt idempotency anchor for the `StockReceived` outbox event, closing ADR 0056 accepted-limitation #1) | SaleRecorded, StocktakeCompleted (menu-item AND ingredient flows), RegisterSessionClosed, … | EntitlementGranted/Revoked, UserOutletAssignmentChanged, … | V1–V43 |
| **carwash-service** | 2nd vertical: `wash`, entitlement-gated; metrics; POS-parity foundation ported from restaurant-service — `pricing` (tax_charge_rule, VAT_CARWASH key), `payment` (CashProvider/DigitalProvider/PaymentProviderRegistry port + `carwash_payment`; ticket quote/checkout/capture — `TicketCaptureWriter.capture(ticketId)` is the idempotent digital-capture unit, ADR 0023), `outletref` (`user_outlet_assignment_ref` + `OutletAccessGuard`); **ADR 0049 P4 — `OperatorRequiredGuard` device-ticket enforcement in `TicketWriter#create` (enforce-only, metric subject stays the washer)** | SaleRecorded, MetricPublished | EntitlementGranted/Revoked, EmployeeChanged, AssignmentChanged, UserOutletAssignmentChanged | V1–V7 |
| **barbershop-service** | 3rd vertical: barbershop ticket flow (quote/checkout/capture) on the carwash foundation, entitlement-gated; sells gift cards at the POS; loyalty/gift-card redemption checks via local read models (V5); **ADR 0049 P4 — `OperatorRequiredGuard` device-ticket enforcement in `TicketWriter#create` (enforce-only, metric subject stays the barber)** | SaleRecorded, MetricPublished, GiftCardSold | EntitlementGranted/Revoked, EmployeeChanged, AssignmentChanged, UserOutletAssignmentChanged, GiftCardStateChanged, LoyaltyBalanceChanged | V1–V5 |
| **loyalty-service** | loyalty points + gift-card ledger across verticals (ADR 0026/0027): balances from sale/refund/void flows, gift-card lifecycle, redemption anomaly flags | GiftCardStateChanged, LoyaltyBalanceChanged, LoyaltyRedemptionFlagged | GiftCardSold, SaleRecorded, SaleRefunded, SaleVoided | V1–V2 |
| **employee-service** | HR: employee/contract/assignment (PII-encrypted, console CRUD + list APIs) + **payroll engine** (gross-to-net, flagged-illustrative statutory; runtime setup/compensation/run-read APIs) + **own-sales commission** (`PERCENT_OF_METRIC` earning rule; config at `/api/v1/employees/{id}/compensation/{pkgId}/commission`) + **employee self-service** `/api/v1/me/**` (profile masked, own payslips w/ real amounts, `GET /me/sales`) via the V7 `employee.user_id`↔Keycloak-`sub` link + **ADR 0049 P1 operator PIN/session** (`operator_pin` Argon2id, `PUT /api/v1/employees/{id}/operator-pin` owner/manager, `POST /api/v1/operators/session` mints an HMAC operator token — no vertical consumes it yet) + **ADR 0049 per-outlet operator-PIN policy** (`outlet_operator_policy`, absent row defaults `require_pin=true`; `GET /api/v1/operators/policy?businessId=` POS_ROLES, `PUT /api/v1/employees/outlet-pin-policy/{businessId}` owner/manager — a `require_pin=false` outlet skips the PIN branch in `OperatorSessionWriter#verifyAndMint` entirely and the roster switches to assigned+linked-only, no event/contract change). Gateway-routed (`/api/v1/employees/**`, `/api/v1/payroll-runs/**`, `/api/v1/payroll-setup/**` — DASHBOARD_ROLES; `/api/v1/me/**` — ME_ROLES incl. employee; `/api/v1/operators/**` — POS_ROLES; local port 8084) | EmployeeChanged, AssignmentChanged, PayrollPosted, LaborCostAllocated | OrgUnitCreated/Changed, MetricPublished, PeriodSealed | V1–V17 |
| **finance-service** | the consolidation core: dimensional ledger, P&L, FX, group consolidation. **The big one.** **ADR 0067 Phase B (V54)** — `bill_line.is_inventory` per-line routing flag (the seam `BillWriter`'s upcoming EXPENSE_NET/INVENTORY_NET net split reads; migration-only, default FALSE = today's behaviour unchanged) | ConsolidationClosed, TrialBalancePublished | SaleRecorded, ExpenseRecorded, PayrollPosted, LaborCostAllocated, GroupDefined, GroupMembershipChanged, TrialBalancePublished | V1–V54 |
| **payment-service** | QRIS payment modes + PSP charge lifecycle (ADR 0045): `payment_settings` (mode MANUAL\|STATIC\|GATEWAY, merchant static QRIS image — object-store-backed since ADR 0048, merchant-own Midtrans creds — AES-encrypted), `payment_charge` (dynamic QRIS per transaction, settled by the signed inbound Midtrans webhook at `/api/v1/psp-webhooks/midtrans/{companyId}`) | PaymentChargeSucceeded | — | V1–V5 |
| **entitlement-service** | module entitlements per company + billing | EntitlementGranted/Revoked | CompanyCreated | V1 |
| **notification-service** | notify + (stub) delivery | DeliveryReceipt | ConsolidationClosed | V1 |

Ports/creds for local run: see RUNBOOK. Each non-gateway service connects as its own non-superuser
Postgres role `<svc>_service` (so **RLS is enforced at runtime**).

## finance-service feature packages (the consolidation core, by area)
`revenue` (SaleRecorded→ledger+consolidated_revenue) · `expense` (ExpenseRecorded) · `pnl` (dashboard P&L — GL-derived == income statement, ADR 0065; `consolidated_pnl` now write-path currency-guard only) ·
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
| the POS offline queue / provisional pricing / sync | `frontend/console/src/features/pos/offline/` (ADR 0028; parity fixture `pricing-parity.fixture.json` asserted by BOTH restaurant `ProvisionalPricingFixtureTest` and the vitest suite) |
| self-order QR (anonymous surface) | restaurant `selforder/` + `selforderaccess/` (token filter, mint/rotate), gateway `selfOrderRoute` + `AnonymousTenantHeaderStripFilter`, mini app `frontend/self-order/`, ADR 0029 |
| the customer display | `frontend/console/src/features/pos/display/` (BroadcastChannel `pos-display:{outlet}`, zero backend) |
| ingredient inventory / stock opname (bahan) | restaurant `inventory/` (catalog + receive/set + ingredient stocktake → reuses `StocktakeCompleted`), console `frontend/console/src/features/inventory/` + `features/stocktake/StocktakeSheet.tsx`, ADR 0046 |
| recipes/BOM + HPP (resep, per-sale ingredient depletion) | restaurant `recipe/` (V34 `recipe_line`; `GET/PUT /api/v1/menu/{itemId}/recipe`, `GET /api/v1/menu/hpp-summary`; `IngredientDepletionWriter` beside `StockDeductionWriter` at every sale site), console `features/menu/RecipeDrawer.tsx` + `recipeApi.ts`, ADR 0050 (phases B/C = purchasing + perpetual COGS, contracts pinned) |
| QRIS modes / the PSP (Midtrans) webhook | payment-service `settings/` + `charge/` (`WebhookService` = the anonymous signature-verified edge at `/api/v1/psp-webhooks/midtrans/{companyId}`), gateway `pspWebhookRoute`, console `frontend/console/src/features/payments/`, ADR 0045 |
| thermal printing (ESC/POS, transports) | `frontend/console/src/lib/escpos/` (encoder/receipt/transport/usePrinter; ADRs 0039, 0041, 0043) |
| the Android till app (native shell + print bridge) | `frontend/native-till/` — Capacitor thin-client over the live console + `NativePrintPlugin.kt` (SPP byte pipe); OWN Android/Gradle build, never in root `./gradlew` (ADR 0043) |
| offline-replay server guards / effective-rules endpoints | each vertical's `…/order|ticket/service/OfflineReplayGuard.java` + `…/pricing/controller/PricingController.java` |
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
