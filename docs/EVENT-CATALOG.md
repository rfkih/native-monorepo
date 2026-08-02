# Event Catalog

> The inter-service contract for Native. **Read this before touching any event.**
> CLAUDE.md rule 7 / the "Never" list: no event may exist without an entry here AND
> a registered Avro schema, and every schema change must be **backward-compatible
> only**.

All events are published through the **transactional outbox** (rule 3) and tailed to
Kafka by Debezium. Consumers are **idempotent** (dedupe by event id/key). Avro is the
payload format; every schema lives **once** in **`libs/contracts`**
(`libs/contracts/src/main/resources/avro/*.avsc`, on the classpath at `avro/<Event>.avsc`).
Producers and consumers both depend on that module — there are no per-service schema copies
to drift (ADR 0003). A Schema Registry is the eventual home at the first service split.

**Wire transport (Kafka record value).** The outbox `payload` column is `bytea` (raw
Avro bytes). Debezium decodes a `bytea` as a `java.nio.ByteBuffer`, which Connect's
`ByteArrayConverter` cannot ship, so the connector emits the payload as **base64 text**
(`binary.handling.mode=base64` + a `StringConverter`) — see `docker/README.md`. The
record value is therefore the **base64-encoded Avro bytes** (a pure transport encoding,
**no Confluent Schema Registry serde**). Every consumer base64-decodes the value back to
the raw Avro bytes with `libs/events Base64ByteArrayDeserializer` and then decodes those
bytes with `libs/events AvroSerde` against the shared `libs/contracts` schema — so where a section below
says a consumer "reads the outbox payload as **raw Avro bytes** via `libs/events
AvroSerde`", that is the post-base64-decode payload; `AvroSerde`'s raw-bytes contract is
unchanged.

**Kafka record headers.** Each Kafka record carries three standard headers in addition
to the value:

| Header | Source | Purpose |
|---|---|---|
| `id` | outbox `id` column | The durable event UUID; consumers dedupe on this (not the Kafka offset) |
| `company_id` | outbox `company_id` column | The owning tenant |
| `traceparent` | outbox `traceparent` column (nullable) | W3C trace-context header (ADR 0010 #13 — outbox→Kafka trace continuity). Stamped by `OutboxWriter` from the active Micrometer span at write time; absent when the column is NULL (no span in scope). Consumers restore the trace context via Spring Kafka observation (`setObservationEnabled(true)`) so their listener span is a child of the producer span. A missing header starts a new root span — safe and backward-compatible. |

---

## Starter event table (planned contracts)

Seeded from ARCHITECTURE.md §5. These are the *planned* inter-service contracts; an
event is only **live** once it has a concrete section below with its registered Avro
schema. Until then a row here is documentation of intent, not a shippable contract.
A **SCHEMA REGISTERED** status sits between the two: the `.avsc` + section + contract
triads exist (a shippable contract, contracts-first), but no runtime producer/consumer
has landed yet — the status names the phases that will land them.

| Event | Producer | Consumers | Key fields | Status |
|---|---|---|---|---|
| **`CompanyCreated`** | **org-service** | **entitlement, finance, verticals** | **company_id, legal_employer_id, base_currency, default_language** | **LIVE (M1.2)** |
| **`OrgUnitCreated`** | **org-service** | **employee, verticals, finance** | **org_unit_id, type, parent_id, company_id** | **LIVE (#18); finance consumer Phase 2 outlet-scoping** |
| **`OrgUnitChanged`** | **org-service** | **employee, verticals, finance** | **org_unit_id, type, parent_id, company_id** | **LIVE (#18); finance consumer Phase 2 outlet-scoping** |
| **`EntitlementGranted`** | **entitlement-service** | **shell, all services** | **company_id, module_key** | **LIVE** |
| **`EntitlementRevoked`** | **entitlement-service** | **shell, all services** | **company_id, module_key** | **LIVE** |
| **`EmployeeChanged`** | **employee-service** | **verticals** | **employee_id, company_id, status** | **LIVE (#19)** |
| **`AssignmentChanged`** | **employee-service** | **verticals, finance** | **employee_id, org_unit_id, reporting_to, effective_from/to** | **LIVE (#19)** |
| **`MetricPublished`** | **carwash-service, restaurant-service** (verticals) | **employee** | **metric_key, period, grain, subject_id, value, source_business_id** | **LIVE (#20; restaurant own-sales commission)** |
| **`PeriodSealed`** | **verticals** | **employee, finance** | **company_id, business_id, period** | **LIVE (employee consumer #23)** |
| **`SaleRecorded`** | **restaurant-service + carwash-service + barbershop-service** (verticals) | **finance** | **sale_id, company_id, business_id, amount_minor, currency, occurred_at; Phase 2: subtotal_minor, discount_minor, service_charge_minor, tax_minor, tax_rule_version, uses_illustrative_rules (all nullable); Phase 4 (ADR 0027): loyalty_member_id, loyalty_redeemed_points, loyalty_redeemed_minor, gift_card_id, gift_card_redeemed_minor (all nullable)** | **LIVE (M1.4 / #20 / Phase 2 breakdown / Phase 4 loyalty+gift-card schema)** |
| **`SaleVoided`** | **restaurant-service** | **finance** | **void_id, sale_id, payment_id, company_id, business_id, amount_minor, currency, occurred_at, tender_type** | **LIVE (ADR 0006, slice 4)** |
| **`SaleRefunded`** | **restaurant-service** | **finance** | **refund_id, sale_id, payment_id, company_id, business_id, refund_amount_minor, currency, total_refunded_minor, occurred_at, tender_type** | **LIVE (ADR 0006, slice 4)** |
| **`GiftCardSold`** | **restaurant-service + carwash-service + barbershop-service** (verticals) | **finance-service, loyalty-service** | **gift_card_sale_id, gift_card_id, company_id, business_id, amount_minor, currency, tender_type, occurred_at** | **LIVE (ADR 0027, Phase 4): vertical producers (gift-card sell endpoints) + finance liability-posting consumer + loyalty-service card-materialising consumer all built** |
| **`LoyaltyBalanceChanged`** | **loyalty-service** | **the verticals** | **member_id, company_id, points_balance (absolute), balance_seq (monotonic), reason, occurred_at** | **LIVE (ADR 0027, Phase 4): loyalty-service producer + vertical consumers built** |
| **`GiftCardStateChanged`** | **loyalty-service** | **the verticals** | **gift_card_id, company_id, state, balance_minor (absolute), currency, balance_seq (monotonic), occurred_at** | **LIVE (ADR 0027, Phase 4): loyalty-service producer + vertical consumers built** |
| **`LoyaltyRedemptionFlagged`** | **loyalty-service** | **notification-service** | **flag_id, company_id, business_id, sale_id, member_id, gift_card_id, shortfall_minor, shortfall_points, occurred_at** | **LIVE (ADR 0027, Phase 4): loyalty-service producer + vertical consumers built** |
| **`ExpenseRecorded`** | **verticals** | **finance** | **expense_id, company_id, business_id, amount_minor, currency, gl_hint, occurred_at** | **LIVE (finance consumer #21)** |
| **`PayrollPosted`** | **employee-service** | **finance** | **payroll_run_id, run_seq, run_type, company_id, period, base_currency, totals, rule_versions, uses_illustrative_rules, posted_at** | **LIVE (#23); finance consumer #23; run_type added ADR 0032 P4** |
| **`LaborCostAllocated`** | **employee-service** | **finance** | **payroll_run_id, run_seq, run_type, company_id, period, outlet_id, gl_account, amount_minor, currency, uses_illustrative_rules, unallocated** | **LIVE (#23); finance consumer #23; run_type added ADR 0032 P4** |
| **`PayrollLiabilitiesPosted`** | **employee-service** | **finance** | **payroll_run_id, company_id, period, run_seq, run_type, base_currency, employer_cost_total_minor, liabilities[], uses_illustrative_rules, posted_at** | **LIVE (ADR 0032, Track P phase P4); finance consumer P4** |
| **`ExpenseClaimApproved`** | **employee-service** | **finance** | **claim_id, company_id, org_unit_id, employee_id, amount_minor, currency, gl_hint, expense_date, approved_at** | **SCHEMA REGISTERED (ADR 0030, E0); producer E1, finance consumer E2** |
| **`ExpenseClaimVoided`** | **employee-service** | **finance** | **claim_id, company_id, org_unit_id, employee_id, amount_minor, currency, gl_hint, approved_at, voided_at** | **SCHEMA REGISTERED (ADR 0030, E0); producer E7, finance consumer E2** |
| **`ExpenseReimbursementSettled`** | **employee-service** | **finance** | **claim_id, company_id, org_unit_id, employee_id, amount_minor, currency, settlement_kind, payroll_run_id?, run_seq?, settled_at** | **SCHEMA REGISTERED (ADR 0030, E0); producers E4/E5, finance consumer E2** |
| **`UserOutletAssignmentChanged`** | **org-service** | **restaurant-service** (Phase 5 outlet-scoping), **carwash-service** (outlet-scoping foundation) | **assignment_id, user_id, company_id, org_unit_id, change_kind, effective_from, effective_to** | **LIVE (Phase 5); carwash-service consumer added ahead of its ticket/checkout POS-parity work** |
| **`GroupDefined`** | **org-service** | **finance** | **group_id, lead_company_id, reporting_currency, name** | **LIVE (P3d SEAM 1); finance consumer P3d SEAM 1** |
| **`GroupMembershipChanged`** | **org-service** | **finance** | **group_id, member_company_id, change_kind, effective_from, effective_to** | **LIVE (P3d SEAM 1); finance consumer P3d SEAM 1** |
| **`TrialBalancePublished`** | **finance** (member within-company close) | **finance** (group consolidation) | **company_id, group_id, period, base_currency, reconciled, uses_illustrative_rules, lines[]** | **LIVE (CONSUMER P3d SEAM 2 group_trial_balance ingest; PRODUCER P3d SEAM 4a within-company close)** |
| **`ConsolidationClosed`** | **finance** (within-company + group close) | **shell, notification** | **company_id (or group_id), period** | **LIVE (PRODUCER P3d SEAM 4a; notification consumer #22)** |
| **`DeliveryReceipt`** | **notification-service** | **(audit/observability sinks; re-send policy)** | **notification_id, company_id, channel, status, provider_ref, delivered_at** | **LIVE (#22)** |

---

## Live events

### `CompanyCreated`

Emitted by org-service when a company (a tenant) is created in the create-company
flow (M1.2 — the tenant keystone). A company's **base currency** and **default
language** are set at creation and carried on this event so downstream services can
seed their slice of the new tenant without a synchronous call (rule 2).

- **Producer:** `org-service`
- **Consumers:** `entitlement-service` (seed the tenant's entitlements — treat as fully
  entitled in the validation slice), `finance-service` (register the company's
  consolidation scope + base currency), the verticals (cache their slice of the org
  tree / company config).
- **Aggregate type / partition key:** `company` / `company_id`
- **Outbox `event_type`:** `CompanyCreated`
- **Schema:** `services/org-service/src/main/resources/avro/CompanyCreated.avsc`
- **Full name:** `id.co.nativeapp.events.org.CompanyCreated`

**Base currency is an ISO-4217 code, immutable once set** (CLAUDE.md "Settings live at
creation"). It is a currency *code*, never a monetary amount and never a float. The
company is its own tenant, so `company_id` is the new company's id; in M1.2 a company
is its own legal employer, so `legal_employer_id == company_id` (the dedicated
legal-employer aggregate arrives with the full org tree later).

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `company_id` | `string` | The new tenant / company aggregate id (UUID as string); the partition key |
| `legal_employer_id` | `string` | The legal employer this company is — consolidation + entitlement boundary (UUID as string) |
| `base_currency` | `string` | The company's base (functional) currency: an ISO-4217 code (e.g. `IDR`, `USD`); immutable |
| `default_language` | `string` | The company default language (e.g. `en`, `id`); a per-user override lives on the user profile |

**Avro schema**

```json
{
  "type": "record",
  "name": "CompanyCreated",
  "namespace": "id.co.nativeapp.events.org",
  "doc": "Emitted by org-service when a company (tenant) is created; consumed by entitlement-service (to seed entitlements), finance-service (to register the consolidation/base-currency scope), and the verticals. Carries the company's immutable base_currency and default_language, set at creation (CLAUDE.md 'Settings live at creation').",
  "fields": [
    {"name": "company_id", "type": "string", "doc": "The new tenant / company aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "legal_employer_id", "type": "string", "doc": "The legal employer this company is — the consolidation + entitlement boundary (UUID as string)."},
    {"name": "base_currency", "type": "string", "doc": "The company's base (functional) currency: an ISO-4217 code (e.g. IDR, USD). Immutable once transactions exist."},
    {"name": "default_language", "type": "string", "doc": "The company default language (e.g. en, id). A per-user override lives on the user profile."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a
default (e.g. an optional `display_name` as `["null","string"]` with `default: null`).
Never add a required field without a default, never remove or rename a field, never
change a field's type. The contract test (`CompanyCreatedContractTest`) enforces this —
it asserts the schema is backward-compatible with itself and with an
added-optional-field variant, and rejects a new required field without a default.

### `OrgUnitCreated`

Emitted by org-service when an `org_unit` (a node in the company's self-referencing org
tree — `business_unit > outlet > team`, ADR 0012) is created, including the company's
root business unit AND its seeded default outlet created during the company bootstrap
(#18 — the full org tree; ADR 0012 — every new business unit seeds one default outlet).
Consumed by employee-service, the verticals, and finance-service so each caches its
slice of the org tree without a synchronous call (rule 2).

- **Producer:** `org-service`
- **Consumers:** `employee-service` (anchors assignments on the org tree),
  the verticals (local staff/org read model), `finance-service` (dimensional ledger
  org dimensions).
- **Aggregate type / partition key:** `org_unit` / `org_unit_id`
- **Outbox `event_type`:** `OrgUnitCreated`
- **Schema (single source of truth, ADR 0003):** `libs/contracts/src/main/resources/avro/OrgUnitCreated.avsc`
- **Full name:** `id.co.nativeapp.events.org.OrgUnitCreated`

The org tree is strictly nested and enforced in the `OrgUnit` aggregate: a `business_unit`
is a top-level node (`parent_id` null); an `outlet` hangs under a `business_unit`; a `team`
under an `outlet`. `parent_id` is therefore a nullable union (`["null","string"]`, default
`null`).

A `business_unit` additionally carries a **`vertical`** — the kind of business it runs
(`restaurant | carwash | barbershop`), REQUIRED at creation and immutable after. It is a
**LOWERCASE module-key-style string** aligned with entitlement-service's
`module_catalog.module_key` vocabulary — deliberately NOT the uppercase enum-name casing
`type` uses. Outlet/team nodes carry `null` (an outlet inherits its parent business unit's
vertical); events produced before the field existed also decode as `null` — those business
units are `restaurant` by the org-service V6 backfill. Per-company entitlement (which
modules a company MAY use) remains entitlement-service's separate, company-level concept;
this field only aligns vocabulary with it. Consumers stay opaque to the field.

**Key fields** (ARCHITECTURE.md §5: `org_unit_id`, `type`, `parent_id`, `company_id`)

| Field | Avro type | Meaning |
|---|---|---|
| `org_unit_id` | `string` | The org_unit aggregate id (UUID as string); the partition key |
| `company_id` | `string` | The owning tenant / company id (UUID as string) |
| `type` | `string` | The org-unit kind: `business_unit` \| `outlet` \| `team` |
| `parent_id` | `["null","string"]` (default `null`) | The parent org_unit id, or null for a top-level node |
| `legal_employer_id` | `string` | The legal employer this node belongs to (UUID as string) |
| `name` | `string` | The org-unit display name |
| `vertical` | `["null","string"]` (default `null`) | Business vertical of a `business_unit` (lowercase `restaurant` \| `carwash` \| `barbershop`); null for outlet/team + pre-V6 events |

**Avro schema**

```json
{
  "type": "record",
  "name": "OrgUnitCreated",
  "namespace": "id.co.nativeapp.events.org",
  "doc": "Emitted by org-service when an org_unit (a node in the company's business_unit > outlet > team tree, ADR 0012) is created; consumed by employee-service, the verticals, and finance-service so each can cache its slice of the org tree without a synchronous call (rule 2). Key fields per ARCHITECTURE.md §5: org_unit_id, type, parent_id, company_id.",
  "fields": [
    {"name": "org_unit_id", "type": "string", "doc": "The org_unit aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string)."},
    {"name": "type", "type": "string", "doc": "The org-unit kind: business_unit | outlet | team."},
    {"name": "parent_id", "type": ["null", "string"], "default": null, "doc": "The parent org_unit id (UUID as string), or null for a top-level node (a business_unit)."},
    {"name": "legal_employer_id", "type": "string", "doc": "The legal employer this node belongs to (UUID as string)."},
    {"name": "name", "type": "string", "doc": "The org-unit display name."},
    {"name": "vertical", "type": ["null", "string"], "default": null, "doc": "The business vertical of a business_unit node, as a LOWERCASE module-key-style string (restaurant | carwash | barbershop) aligned with the entitlement module_catalog.module_key vocabulary — deliberately NOT the UPPERCASE enum-name casing 'type' uses. Null for outlet/team nodes (an outlet inherits its parent business_unit's vertical) and on events produced before this field existed (those business units are 'restaurant' by the org-service V6 backfill). Immutable after creation. MUST remain the LAST field (positional decode safety)."}
  ]
}
```

**Compatibility.** Backward-compatible only (add optional fields with a default; never a
new required field without a default, never remove/rename/retype). Enforced by
`OrgUnitEventContractTest` (parse + `AvroSerde` round-trip + back-compat for-change /
against-break + the old-reader-decodes-new-writer pin for the appended `vertical`).

### `OrgUnitChanged`

Emitted by org-service when an `org_unit` is **renamed**, **moved** to a new parent,
**deactivated**, or **reactivated** via `PATCH /api/v1/org-units/{orgUnitId}` (#18, #25). One
event per effective change. **Deactivation cascades** to the node's active subtree (one
`DEACTIVATED` event per node), so deactivating an outlet takes its teams down too;
**reactivation** (`REACTIVATED`) applies to the node alone and requires an active parent.
Consumed by the same set as `OrgUnitCreated` to update their cached slice of the org tree; it
carries the node's new state so a consumer applies it idempotently (the employee-service
consumer keys on the `active` flag, not `change_kind`, so it applies DEACTIVATED/REACTIVATED
uniformly).

- **Producer:** `org-service`
- **Consumers:** `employee-service`, the verticals, `finance-service` (same as
  `OrgUnitCreated`).
- **Aggregate type / partition key:** `org_unit` / `org_unit_id`
- **Outbox `event_type`:** `OrgUnitChanged`
- **Schema (single source of truth, ADR 0003):** `libs/contracts/src/main/resources/avro/OrgUnitChanged.avsc`
- **Full name:** `id.co.nativeapp.events.org.OrgUnitChanged`

**Key fields** (ARCHITECTURE.md §5: `org_unit_id`, `type`, `parent_id`, `company_id`)

| Field | Avro type | Meaning |
|---|---|---|
| `org_unit_id` | `string` | The org_unit aggregate id (UUID as string); the partition key |
| `company_id` | `string` | The owning tenant / company id (UUID as string) |
| `type` | `string` | The org-unit kind (immutable once created) |
| `parent_id` | `["null","string"]` (default `null`) | The CURRENT parent after the change, or null |
| `change_kind` | `string` | What changed: `RENAMED` \| `MOVED` \| `DEACTIVATED` \| `REACTIVATED` |
| `name` | `string` | The org-unit display name after the change |
| `active` | `boolean` | Whether the node is active after the change (false after deactivate, true after reactivate) |
| `vertical` | `["null","string"]` (default `null`) | The node's (immutable) business vertical, carried on every change so consumers stay stateless; null for outlet/team + pre-V6 events |

**Avro schema**

```json
{
  "type": "record",
  "name": "OrgUnitChanged",
  "namespace": "id.co.nativeapp.events.org",
  "doc": "Emitted by org-service when an org_unit is renamed, moved to a new parent, deactivated, or reactivated; consumed by employee-service, the verticals, and finance-service to update their cached slice of the org tree. Deactivation cascades to the active subtree (one event per node). Key fields per ARCHITECTURE.md §5: org_unit_id, type, parent_id, company_id. Carries the new state so a consumer can apply it idempotently.",
  "fields": [
    {"name": "org_unit_id", "type": "string", "doc": "The org_unit aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string)."},
    {"name": "type", "type": "string", "doc": "The org-unit kind: business_unit | outlet | team (immutable once created)."},
    {"name": "parent_id", "type": ["null", "string"], "default": null, "doc": "The CURRENT parent org_unit id (UUID as string) after the change, or null for a top-level node."},
    {"name": "change_kind", "type": "string", "doc": "What changed: RENAMED | MOVED | DEACTIVATED | REACTIVATED. A string (not an Avro enum), so adding a kind is backward-compatible; consumers that key on the 'active' flag handle DEACTIVATED/REACTIVATED uniformly."},
    {"name": "name", "type": "string", "doc": "The org-unit display name after the change."},
    {"name": "active", "type": "boolean", "doc": "Whether the node is active after the change (false after a deactivation, true after a reactivation)."},
    {"name": "vertical", "type": ["null", "string"], "default": null, "doc": "The business vertical of a business_unit node (LOWERCASE module-key string: restaurant | carwash | barbershop — NOT the uppercase enum-name casing 'type' uses). IMMUTABLE — carried on every change event so consumers stay stateless. Null for outlet/team nodes and pre-vertical events. MUST remain the LAST field (positional decode safety)."}
  ]
}
```

**Compatibility.** Backward-compatible only, enforced by `OrgUnitEventContractTest`.

### `SaleRecorded`

The first live event (M1.4 — the validation slice). Emitted by a vertical when a sale
is recorded; consumed by finance-service to post to the ledger.

- **Producer:** `restaurant-service`, `carwash-service` (#20 — the 2nd vertical; a recorded wash
  emits `SaleRecorded` with `business_id` = the carwash outlet, so finance consolidates carwash
  revenue alongside restaurant), and `barbershop-service` (ADR 0024 — the 3rd vertical; ticket
  checkout, the only revenue path). Since the carwash POS (ADR 0023), carwash has TWO producing
  paths: the ticket checkout/capture path sends the **full Phase-2 breakdown + `tender_type`**
  exactly like restaurant, while the legacy `POST /api/v1/washes` path still sends all-null
  breakdown (finance's null fall-back path, unchanged). barbershop (ADR 0024) has no legacy path —
  its ticket checkout is the only producer and always sends the full breakdown.
- **Consumers:** `finance-service` (ledger posting + consolidated-revenue read model) — **LIVE (M1.5)**
- **Aggregate type / partition key:** `sale` / `sale_id`
- **Outbox `event_type`:** `SaleRecorded`
- **Schema (single source of truth, ADR 0003):** `libs/contracts/src/main/resources/avro/SaleRecorded.avsc` — every producer (restaurant, carwash, barbershop) and finance's consumer resolve the SAME classpath resource (`avro/SaleRecorded.avsc`), so a producer and its consumer can no longer structurally drift; there is exactly **one physical copy of this file in the repo**. (Historical note: before ADR 0003 each service kept its own copy under `services/<service>/src/main/resources/avro/`; those per-service copies were deleted when the schema consolidated into `libs/contracts` — a reference to a `services/.../avro/SaleRecorded.avsc` path elsewhere in this document or in prior session notes describes that pre-ADR-0003 state and no longer exists on disk.) Each service's own `SaleRecordedContractTest` (restaurant, carwash, barbershop, finance) still asserts its runtime view of the schema stays backward-compatible with the historical producer shape (rule 7) — the tests are per-service because each service builds/decodes records differently, even though the `.avsc` they load is identical. The finance consumer reads the outbox payload as **raw Avro bytes** via `libs/events AvroSerde` (no Schema Registry serde), deduping by the event UUID (`ProcessedEventStore`) so a re-delivery never double-posts.
- **Full name:** `id.co.nativeapp.events.restaurant.SaleRecorded`

**Money is an integer minor-units amount + an ISO-4217 currency code, never a float**
(rule 8). `amount_minor` is the value in the currency's minor units (cents for USD,
whole rupiah for IDR); `currency` is the ISO-4217 code. `occurred_at` is epoch millis
(UTC) via the Avro `timestamp-millis` logical type.

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `sale_id` | `string` | Sale aggregate id (UUID as string); the partition key |
| `company_id` | `string` | Owning tenant (UUID as string) |
| `business_id` | `string` | Originating business unit (UUID as string) |
| `amount_minor` | `long` | **Grand total** in the currency's minor units — the customer-pays amount. Never a float |
| `currency` | `string` | ISO-4217 currency code (e.g. `IDR`, `USD`) |
| `occurred_at` | `long` (`timestamp-millis`) | When the sale occurred, epoch millis UTC |
| `tender_type` | `["null","string"]` (default `null`) | Payment tender: `CASH` \| `QRIS` \| `CARD`, or `null` for legacy/no-payment sales. Finance routes the GL debit clearing account by tender: `null`/`CASH` → `CASH_CLEARING`, `QRIS` → `QRIS_CLEARING`, `CARD` → `CARD_CLEARING`. Backward-compatible addition — ADR 0006 slice 2 |
| `subtotal_minor` | `["null","long"]` (default `null`) | Sum of order-line totals **before** discount/tax/service-charge, in minor units. `null` for legacy producer paths (carwash `POST /washes`); finance falls back to `subtotal == amount` (Phase 1). Phase 2 addition; the carwash ticket path (ADR 0023) populates it |
| `discount_minor` | `["null","long"]` (default `null`) | Order-level discount in minor units, or `null` (treated as 0 by finance). Phase 2 addition. **Since the promotions engine (ADR 0026) this is the COLLAPSED SUM of every deduction — automatic promo rules, at most one coupon, and the manual discount; per-rule detail is vertical-local (`applied_promotion`), never on the event** |
| `service_charge_minor` | `["null","long"]` (default `null`) | Service charge in minor units, or `null` (treated as 0 by finance). Phase 2 addition |
| `tax_minor` | `["null","long"]` (default `null`) | Tax amount in minor units, or `null` (treated as 0 by finance). Phase 2 addition |
| `tax_rule_version` | `["null","string"]` (default `null`) | The `rule_version` label of the resolved tax rule (e.g. `PB1_RESTAURANT-v1`), or `null` for legacy producers. Informational — finance stores it for audit |
| `uses_illustrative_rules` | `["null","boolean"]` (default `null`) | `true` when any resolved tax/service-charge rule was `ILLUSTRATIVE_PLACEHOLDER`; `null` treated as `false` by finance. Sticky in the finance GL: an entry is flagged illustrative when this is `true` OR when the posting template is illustrative |
| `loyalty_member_id` | `["null","string"]` (default `null`) | Phase 4 (ADR 0027): the loyalty member (UUID as string) this sale is attributed to for points EARN, or `null` when no member was attached. **NOT PII** — an opaque member id; the member's name/phone stay column-encrypted in loyalty-service and never cross this event (rule 6) |
| `loyalty_redeemed_points` | `["null","long"]` (default `null`) | Phase 4: the number of loyalty points spent on this sale, in loyalty-service's ledger units. Finance **ignores** this field (not money) — carried for traceability only |
| `loyalty_redeemed_minor` | `["null","long"]` (default `null`) | Phase 4: the currency value of redeemed loyalty points, in minor units. A **contra-revenue deduction**, exactly like `discount_minor` — it EXTENDS the reconciliation identity (below). `null` (treated as 0) when no points were redeemed |
| `gift_card_id` | `["null","string"]` (default `null`) | Phase 4: the gift card (UUID as string) redeemed against this sale as a **tender**, or `null` when no gift card was used. Distinct from `loyalty_member_id` — a gift-card redemption is a liability settlement, not a discount |
| `gift_card_redeemed_minor` | `["null","long"]` (default `null`) | Phase 4: the amount of stored value redeemed from the gift card, in minor units — a **tender-settlement** amount (like `tender_type`), NOT a deduction from revenue. Must be `<= amount_minor`. Finance splits the clearing debit: Dr `GIFT_CARD_LIABILITY` for this amount, Dr the tender-clearing account (resolved from `tender_type`) for the residual (`amount_minor - gift_card_redeemed_minor`). `null` (treated as 0) when no gift card was used |

**Reconciliation identity (Phase 2).** When all four breakdown fields are non-null, finance asserts:
`subtotal_minor - discount_minor + service_charge_minor + tax_minor == amount_minor`. A violated
identity is a poison event (misconfigured producer); finance routes the record to the DLT.

**Reconciliation identity — EXTENDED (Phase 4, ADR 0027).** `loyalty_redeemed_minor` joins
`discount_minor` as a deduction (points redemption is a contra-revenue discount, not a tender);
`gift_card_redeemed_minor` does **not** appear in the identity at all — a gift-card redemption is a
**tender settlement** (it changes HOW `amount_minor` is paid, not what `amount_minor` IS), exactly
like `tender_type` never appearing in the Phase 2 identity. The full identity, nulls treated as 0:
`subtotal_minor - discount_minor - loyalty_redeemed_minor + service_charge_minor + tax_minor ==
amount_minor`. This SUPERSEDES the Phase 2 identity above (Phase 2 is the special case
`loyalty_redeemed_minor == 0`); a violated identity is a poison event routed to the DLT, unchanged.

**Avro schema** (single source of truth in `libs/contracts/src/main/resources/avro/SaleRecorded.avsc`)

```json
{
  "type": "record",
  "name": "SaleRecorded",
  "namespace": "id.co.nativeapp.events.restaurant",
  "doc": "Emitted by a vertical (restaurant-service, carwash-service, barbershop-service) when a sale is recorded; consumed by finance-service to post to the ledger. Money is an integer minor-units amount plus an ISO-4217 currency code, never a float (CLAUDE.md rule 8). amount_minor is the GRAND TOTAL (the amount the customer pays). Phase 2 adds an optional price breakdown (subtotal, discount, service charge, tax) for the finance GL to build a multi-line journal entry. A legacy producer (carwash) sends all-null breakdown fields; finance falls back to subtotal==grand total, no tax/discount. Phase 4 (ADR 0027, loyalty + gift cards) adds five optional fields (loyalty_member_id, loyalty_redeemed_points, loyalty_redeemed_minor, gift_card_id, gift_card_redeemed_minor) that EXTEND the reconciliation identity to: subtotal - discount - loyalty_redeemed + service_charge + tax == amount (nulls treated as 0). loyalty_redeemed_minor is a contra-revenue deduction (like discount_minor); gift_card_redeemed_minor is a TENDER settlement, not a deduction from revenue -- finance splits the clearing debit across the gift-card liability account and the residual tender-clearing account. All five are null for producers that predate Phase 4.",
  "fields": [
    {"name": "sale_id", "type": "string", "doc": "The sale aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "business_id", "type": "string", "doc": "The originating business unit (UUID as string)."},
    {"name": "amount_minor", "type": "long", "doc": "Grand total amount in the currency's minor units — the amount the customer pays. Never a float."},
    {"name": "currency", "type": "string", "doc": "ISO-4217 currency code, e.g. IDR or USD."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the sale occurred, epoch millis (UTC)."},
    {"name": "tender_type", "type": ["null", "string"], "default": null, "doc": "The payment tender type: CASH | QRIS | CARD, or null for legacy/no-payment sales. Finance routes the GL debit clearing account by tender (null/CASH -> CASH_CLEARING, QRIS -> QRIS_CLEARING, CARD -> CARD_CLEARING). Backward-compatible: old producers leave it null; old readers ignore it."},
    {"name": "subtotal_minor", "type": ["null", "long"], "default": null, "doc": "Phase 2 price breakdown: sum of line totals before discount and tax, in the currency's minor units. Null for legacy producers (carwash). When null, finance treats subtotal == amount_minor."},
    {"name": "discount_minor", "type": ["null", "long"], "default": null, "doc": "Phase 2 price breakdown: order-level discount amount in minor units (clamped to <= subtotal). Null for legacy producers. When null or 0, no SALES_DISCOUNT GL leg is posted."},
    {"name": "service_charge_minor", "type": ["null", "long"], "default": null, "doc": "Phase 2 price breakdown: service charge amount in minor units. Null for legacy producers. When null or 0, no SERVICE_CHARGE_REVENUE GL leg is posted."},
    {"name": "tax_minor", "type": ["null", "long"], "default": null, "doc": "Phase 2 price breakdown: tax amount in minor units (PB1 / PPN — regime is SME-gated; ILLUSTRATIVE). Null for legacy producers. When null or 0, no TAX_PAYABLE GL leg is posted."},
    {"name": "tax_rule_version", "type": ["null", "string"], "default": null, "doc": "Phase 2: the rule_version label of the resolved tax rule (e.g. ILLUSTRATIVE-2026.1), for traceability. Null for legacy producers or when no tax rule is seeded."},
    {"name": "uses_illustrative_rules", "type": ["null", "boolean"], "default": null, "doc": "Phase 2: true when any resolved tax/service-charge rule was provenance=ILLUSTRATIVE_PLACEHOLDER. Propagated to finance GL entries as uses_illustrative_rules. Null for legacy producers (treated as false by finance)."},
    {"name": "loyalty_member_id", "type": ["null", "string"], "default": null, "doc": "Phase 4 (ADR 0027): the loyalty member (UUID as string) this sale is attributed to for points EARN, or null when no member was attached to the sale. NOT PII -- an opaque member id; the member's name/phone stay encrypted in loyalty-service and never cross this event."},
    {"name": "loyalty_redeemed_points", "type": ["null", "long"], "default": null, "doc": "Phase 4 (ADR 0027): the number of loyalty points spent on this sale, in loyalty-service's ledger units. Finance IGNORES this field (it is not money); carried for traceability only. Null when no points were redeemed."},
    {"name": "loyalty_redeemed_minor", "type": ["null", "long"], "default": null, "doc": "Phase 4 (ADR 0027): the currency value of redeemed loyalty points, in the sale currency's minor units. A CONTRA-REVENUE deduction, exactly like discount_minor -- it EXTENDS the reconciliation identity to: subtotal - discount - loyalty_redeemed + service_charge + tax == amount. Null (treated as 0 by finance) when no points were redeemed."},
    {"name": "gift_card_id", "type": ["null", "string"], "default": null, "doc": "Phase 4 (ADR 0027): the gift card (UUID as string) redeemed against this sale as a TENDER, or null when no gift card was used. Distinct from loyalty_member_id -- a gift card redemption is a liability settlement, not a discount."},
    {"name": "gift_card_redeemed_minor", "type": ["null", "long"], "default": null, "doc": "Phase 4 (ADR 0027): the amount of stored value redeemed from the gift card, in the sale currency's minor units. A TENDER-SETTLEMENT amount (like tender_type), NOT a deduction from revenue -- it must be <= amount_minor. Finance splits the clearing debit: Dr GIFT_CARD_LIABILITY for this amount, Dr the tender-clearing account (resolved from tender_type) for the residual (amount_minor - gift_card_redeemed_minor). Null (treated as 0) when no gift card was used."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a
default (e.g. an optional `channel` as `["null","string"]` with `default: null`).
Never add a required field without a default, never remove or rename a field, never
change a field's type. The contract test
(`SaleRecordedContractTest`) enforces this — it asserts the schema is
backward-compatible with itself and with an added-optional-field variant, and rejects
a new required field without a default. The Phase 2 breakdown fields are all
`["null","type"]` with `default: null`, so existing consumers (carwash producer copy,
finance consumer copy) that do not yet read them continue to function unchanged.

**Phase 4 (ADR 0027, loyalty + gift cards).** The five trailing fields
(`loyalty_member_id`, `loyalty_redeemed_points`, `loyalty_redeemed_minor`, `gift_card_id`,
`gift_card_redeemed_minor`) are all `["null", <type>]` with `default: null`, appended at the END of
the field list (positional decode safety, the same discipline `vertical` follows on
`OrgUnitCreated`/`OrgUnitChanged`) — a purely additive, backward-compatible change. Every
`SaleRecordedContractTest` (restaurant, carwash, barbershop, finance) asserts BOTH evolution
directions: an **old-writer / new-reader** pair (a reader on the current schema decodes bytes from
an already-deployed pre-Phase-4 producer, the five fields defaulting to null) and a **new-writer /
old-reader** pair (an already-deployed pre-Phase-4 reader still decodes bytes from an upgraded
Phase-4 producer, silently dropping the fields it doesn't know) — covering the rolling-deploy
window where verticals and finance pick up ADR 0027 at different times. `loyalty-service` (the new
producer of `LoyaltyBalanceChanged`/`GiftCardStateChanged`, and a consumer of this event for points
EARN attribution) shipped in the later Phase-4 waves: every vertical write path now populates
these fields, finance posts against them (SALE v3 template), and loyalty-service applies
earn/redeem off this event — the schema-first wave landed the contracts and evolution tests
before any machinery, by design.

### `ExpenseRecorded`

Emitted by a vertical when an expense is recorded (#21 — the finance expansion: mapping
rules + dimensional ledger + expenses). Consumed by finance-service to resolve an EXPENSE
`gl_account` via the versioned, effective-dated `mapping_rule` (from `gl_hint`) and post an
EXPENSE `ledger_posting`, then move the consolidated P&L's expense leg.

- **Producer:** the verticals (e.g. `restaurant-service`), as they ship the expense path.
- **Consumers:** `finance-service` (dimensional ledger posting + consolidated P&L read
  model) — **LIVE (finance consumer #21)**.
- **Aggregate type / partition key:** `expense` / `expense_id`
- **Outbox `event_type`:** `ExpenseRecorded`
- **Schema (finance consumer copy):** `services/finance-service/src/main/resources/avro/ExpenseRecorded.avsc`
  — finance owns its own consumer view of the contract; the finance `ExpenseRecordedContractTest`
  asserts the copy stays backward-compatible with the producer schema (rule 7). The finance
  consumer reads the outbox payload as **raw Avro bytes** via `libs/events AvroSerde` (no Schema
  Registry serde), deduping by the event UUID (`ProcessedEventStore`) so a re-delivery never
  double-posts.
- **Full name:** `id.co.nativeapp.events.restaurant.ExpenseRecorded`

**Money is an integer minor-units amount + an ISO-4217 currency code, never a float** (rule
8). `amount_minor` is the value in the currency's minor units; `currency` is the ISO-4217
code. `occurred_at` is epoch millis (UTC) via the Avro `timestamp-millis` logical type — it
drives both the accounting `period` and the **effective `mapping_rule` version** at resolution
time. `gl_hint` is the vertical's expense-category tag (e.g. `cogs`, `supplies`, `utilities`)
which finance maps to a `chart_of_account` expense account; **an unrecognised hint fails safe
to the suspense account** (the expense is still posted — money is never silently dropped, HR-3
— but quarantined for reclassification), it is not DLT'd. Only an undecodable payload or a
missing `id` header routes the record to `ExpenseRecorded.DLT`.

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `expense_id` | `string` | Expense aggregate id (UUID as string); the partition key |
| `company_id` | `string` | Owning tenant (UUID as string) |
| `business_id` | `string` | Originating business unit (UUID as string) |
| `amount_minor` | `long` | Amount in the currency's minor units — never a float |
| `currency` | `string` | ISO-4217 currency code (e.g. `IDR`, `USD`) |
| `gl_hint` | `string` | Expense-category hint finance maps to an expense account via `mapping_rule` |
| `occurred_at` | `long` (`timestamp-millis`) | When the expense occurred, epoch millis UTC |

**Avro schema**

```json
{
  "type": "record",
  "name": "ExpenseRecorded",
  "namespace": "id.co.nativeapp.events.restaurant",
  "doc": "Emitted by a vertical when an expense is recorded; consumed by finance-service to resolve the EXPENSE gl_account via the versioned, effective-dated mapping_rule (from gl_hint) and post an EXPENSE ledger_posting. Money is an integer minor-units amount plus an ISO-4217 currency code, never a float (CLAUDE.md rule 8). An unrecognised gl_hint fails safe to the suspense account (money never dropped).",
  "fields": [
    {"name": "expense_id", "type": "string", "doc": "The expense aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "business_id", "type": "string", "doc": "The originating business unit (UUID as string)."},
    {"name": "amount_minor", "type": "long", "doc": "Amount in the currency's minor units. Never a float."},
    {"name": "currency", "type": "string", "doc": "ISO-4217 currency code, e.g. IDR or USD."},
    {"name": "gl_hint", "type": "string", "doc": "The expense category hint (e.g. cogs, supplies, utilities); finance resolves it to a chart_of_account expense account via mapping_rule. An unrecognised hint fails safe to the suspense account."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the expense occurred, epoch millis (UTC). Drives the accounting period and the effective mapping_rule version."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default
(e.g. an optional `vendor_id` as `["null","string"]` with `default: null`). Never add a
required field without a default, never remove or rename a field, never change a field's type.
The contract test (`ExpenseRecordedContractTest`) enforces this — it asserts the consumer copy
parses, round-trips a `GenericRecord` through `AvroSerde`, stays backward-compatible with the
producer schema, and rejects a new required field without a default (the triad).

### `ExpenseClaimApproved`

Emitted by employee-service when a manager APPROVES an employee expense claim (ADR 0030 —
the expense-claims program). **Expense recognition happens at approval**: finance-service
consumes this to resolve the category expense account from `gl_hint` via the effective
`mapping_rule` (suspense fail-safe), post Dr expense / **Cr `2600 Employee Expense Payable`**
(`AccountRole EMPLOYEE_EXPENSE_PAYABLE`), append the dimensional EXPENSE `ledger_posting`
under `business_id = org_unit_id`, and accumulate the consolidated P&L expense leg.

This is a NEW contract, deliberately **not** an evolution of `ExpenseRecorded`: that event's
consumer credits CASH_CLEARING, and overloading it would make an already-deployed finance post
claims as cash expenses during a rolling-deploy window (semantically wrong books with a
compatible schema). New event type = new topic = no mixed-semantics window.

- **Producer:** `employee-service` (the approve transition writes the outbox row in the same
  transaction as the SUBMITTED→APPROVED state change — rule 3).
- **Consumers:** `finance-service` (`empexpense` package: payable posting + P&L + GL journal).
- **Aggregate type / partition key:** `expense_claim` / `claim_id`
- **Outbox `event_type`:** `ExpenseClaimApproved`
- **Schema:** `libs/contracts/src/main/resources/avro/ExpenseClaimApproved.avsc` (single source,
  ADR 0003); read off the wire as raw Avro bytes via `libs/events AvroSerde`, deduped by the
  event UUID (`ProcessedEventStore`).
- **Full name:** `id.co.nativeapp.events.employee.ExpenseClaimApproved`

**`employee_id` is a UUID reference, not PII** (contrast `LaborCostAllocated`, which drops it
because per-employee labor cost ≈ salary): a claim amount derives nothing about compensation
and is manager-visible operational data; finance needs it for the payable drill-down. Merchant,
note and the receipt image never cross an event. `org_unit_id` is the all-zeros UUID sentinel
when the employee had no active assignment at `expense_date`. v1 claims are always in the
company base currency. `approved_at` drives both the accounting period and the effective
`mapping_rule` version; `expense_date` (Avro `date`, epoch days) is informational.

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `claim_id` | `string` | Expense-claim aggregate id (UUID as string); the partition key |
| `company_id` | `string` | Owning tenant (UUID as string) |
| `org_unit_id` | `string` | BU/outlet dimension → `ledger_posting.business_id`; all-zeros sentinel when unassigned |
| `employee_id` | `string` | Claiming employee (UUID reference, not PII) |
| `amount_minor` | `long` | Claim amount in minor units — never a float |
| `currency` | `string` | ISO-4217; v1 = company base currency |
| `gl_hint` | `string` | Category hint (`''`, `cogs`, `supplies`, `utilities`) → expense account via `mapping_rule`; unknown → suspense |
| `expense_date` | `int` (`date`) | Date incurred (epoch days); informational |
| `approved_at` | `long` (`timestamp-millis`) | Approval instant; drives period + mapping version |

**Compatibility.** Backward-compatible evolution only (add fields with defaults). The contract
triads (`ExpenseClaimApprovedContractTest` in employee-service AND finance-service) assert the
schema parses, round-trips through `AvroSerde`, remains readable across producer/consumer
copies, and that a required no-default field is rejected.

### `ExpenseClaimVoided`

Emitted by employee-service when an APPROVED, **un-settled, un-linked** claim is VOIDED by a
manager — the correction path (refuse-after-approve is not allowed once money is on the books;
ADR 0030). finance-service posts the exact contra: Dr `2600 Employee Expense Payable` / Cr the
same category expense, and subtracts the P&L expense leg. The contra resolves the
`mapping_rule` effective at the ORIGINAL `approved_at` (so it reverses the exact account the
approval hit) but posts into the period of `voided_at` (the reversal precedent). The producer
guards that a settled or payroll-linked claim can never void; a void arriving after a
settlement at the consumer is a loud logged skip (money already moved — human follow-up).

- **Producer:** `employee-service` (void transition, outbox in-tx).
- **Consumers:** `finance-service` (`empexpense`).
- **Aggregate type / partition key:** `expense_claim` / `claim_id`
- **Outbox `event_type`:** `ExpenseClaimVoided`
- **Schema:** `libs/contracts/src/main/resources/avro/ExpenseClaimVoided.avsc`
- **Full name:** `id.co.nativeapp.events.employee.ExpenseClaimVoided`

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `claim_id` | `string` | Expense-claim aggregate id; the partition key |
| `company_id` | `string` | Owning tenant |
| `org_unit_id` | `string` | The dimension the approval posted under — the contra hits the same `business_id` |
| `employee_id` | `string` | Claiming employee (UUID reference) |
| `amount_minor` | `long` | The exact approved amount being contra'd |
| `currency` | `string` | ISO-4217; matches the approval |
| `gl_hint` | `string` | The approval's category hint — same account resolution |
| `approved_at` | `long` (`timestamp-millis`) | ORIGINAL approval instant — mapping resolved as-of here |
| `voided_at` | `long` (`timestamp-millis`) | Void instant — the contra posts into this period |

**Compatibility.** As `ExpenseClaimApproved` (triads both sides).

### `ExpenseReimbursementSettled`

Emitted by employee-service when an APPROVED claim is reimbursed (ADR 0030), by either path:
**DIRECT** (a manager pays now — `POST /{id}/pay`, AP-payment style) or **PAYROLL** (the claim
rode a payroll run's payslip as the non-taxable `EXPENSE_REIMBURSEMENT` line and the run
POSTED; one event per claim in the CALCULATED→POSTED transaction). finance-service settles the
payable — Dr `2600 Employee Expense Payable` / Cr CASH_CLEARING — a balance-sheet move only;
the expense was recognised at approval.

**Settle-once (the supersession invariant).** Finance keeps a per-claim guard row
(`employee_expense_claim_ledger`, `UNIQUE (company_id, claim_id)` — also the per-claim reconciliation row: recognition, void, and settlement stamps): any second settlement for a
claim — a Kafka re-delivery OR a re-emission after payroll supersession released and re-linked
the claim to a corrective run — is a **logged no-op**. Claim amounts are immutable after
approval, so any re-settlement is financially identical; this single rule collapses every
double-pay window.

- **Producers:** `employee-service` — DIRECT pay (E4) and the payroll run post via
  `ExpenseClaimPayrollLinker` (E5).
- **Consumers:** `finance-service` (`empexpense`).
- **Aggregate type / partition key:** `expense_claim` / `claim_id`
- **Outbox `event_type`:** `ExpenseReimbursementSettled`
- **Schema:** `libs/contracts/src/main/resources/avro/ExpenseReimbursementSettled.avsc`
- **Full name:** `id.co.nativeapp.events.employee.ExpenseReimbursementSettled`

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `claim_id` | `string` | Expense-claim aggregate id; the partition key |
| `company_id` | `string` | Owning tenant |
| `org_unit_id` | `string` | Claim dimension (all-zeros sentinel when unassigned) |
| `employee_id` | `string` | Reimbursed employee (UUID reference) |
| `amount_minor` | `long` | Settled amount = the full approved claim amount |
| `currency` | `string` | ISO-4217; matches the approval |
| `settlement_kind` | `string` | `DIRECT` or `PAYROLL` |
| `payroll_run_id` | `["null","string"]`, default `null` | Settling run when PAYROLL; null for DIRECT |
| `run_seq` | `["null","int"]`, default `null` | Settling run's supersession seq when PAYROLL |
| `settled_at` | `long` (`timestamp-millis`) | Settlement instant; drives the settlement entry's period |

**Compatibility.** As `ExpenseClaimApproved` (triads both sides). The two nullable fields are
the union-with-default idiom — old readers ignore them, old writers omit them.

### `MetricPublished`

Emitted by a vertical when an operational metric is produced (#20 — carwash declares the first live
metric contract). Consumed by employee-service for variable pay / commission. Each vertical declares
its **metric contract**: which `metric_key`s it emits, at which grains (`employee` | `shift` |
`outlet`). The validation layer on the **consumer / employee side** rejects a commission rule
requesting a grain the vertical cannot emit; the producing vertical only DECLARES the contract and
EMITS against it.

- **Producers:**
  - `carwash-service` declares, at the `outlet` grain: `wash_count` (a count of washes) and
    `upsell_amount` (upsell revenue in the wash currency's minor units). On recording a wash it emits
    one `MetricPublished` per declared metric, via the transactional outbox in the same transaction as
    the wash + `SaleRecorded`. **Since the carwash POS (ADR 0023) it ALSO declares, at the `employee`
    grain: `sales_amount`** (the ticket's grand total in minor units), `subject_id` = the ticket's
    `washer_employee_id` — the staff_profile→employee link snapshotted at checkout, so commission
    attributes to the WASHER who did the work (deliberately unlike restaurant, where the subject is
    the cashier who rang it). **Skipped when the ticket's profile is unlinked** (no employee link →
    no commission metric); the outlet-grain metrics are emitted for tickets exactly as for washes
    (`wash_count` 1 per ticket; `upsell_amount` = Σ ADDON line totals).
  - `restaurant-service` declares, at the **`employee` grain**: `sales_amount` (the sale's grand total
    in the sale currency's minor units), `subject_id` = the cashier's Keycloak `sub` (also on
    `sale.created_by`). Emitted **per sale** in the same transaction as `SaleRecorded` (both
    `SaleWriter` choke points). This is the own-sales commission feed: an employee earns a percentage
    of the sales they personally rang. Skipped when the bound actor is not a UUID (the header-trust
    dev recipe's fixed actor) — `metric_input.subject_id` is a UUID column, so a non-UUID subject
    cannot key a row; real logins always carry a UUID `sub`.
- **Consumers:** `employee-service` (variable pay / commission).
- **Delta semantics.** A producer emits **one event per unit of activity** (per wash, per sale), so
  the `value` is a **delta contribution** the consumer ACCUMULATES onto the natural-key
  (`company_id, metric_key, period, grain, subject_id`) row — NOT a snapshot that replaces it. A day
  with several sales at one outlet SUMS. Accumulation is safe because the consumer is idempotent by
  event UUID (`ProcessedEventStore.processOnce`), so each event's contribution is applied exactly
  once. (This corrected a former last-write-wins projection that undercounted same-period activity.)
- **Aggregate type / partition key:** `metric` / `source_business_id` (the carwash outlet, or the
  restaurant outlet the sale was rung at)
- **Outbox `event_type`:** `MetricPublished`
- **Schema:** `services/carwash-service/src/main/resources/avro/MetricPublished.avsc` (producer schema
  of record; `restaurant-service` loads the byte-identical shared `avro/MetricPublished.avsc`)
- **Full name:** `id.co.nativeapp.events.carwash.MetricPublished`

**Money is never a float (rule 8).** The `value` is a `long`: a count for `wash_count`, minor units
of the wash currency for `upsell_amount`. The grain `subject_id` is the subject of the grain (at the
`outlet` grain it is the outlet's id).

**Key fields** (ARCHITECTURE.md §5: `metric_key`, `period`, `grain`, `subject_id`, `value`,
`source_business_id`)

| Field | Avro type | Meaning |
|---|---|---|
| `metric_key` | `string` | The metric this measures, e.g. `wash_count` \| `upsell_amount` |
| `period` | `string` | The period the metric covers (`YYYY-MM-DD` for a daily metric) |
| `grain` | `string` | The aggregation grain: `employee` \| `shift` \| `outlet` |
| `subject_id` | `string` | The id of the grain subject (UUID as string) |
| `value` | `long` | The metric value as a long — a count or minor-units amount; never a float |
| `source_business_id` | `string` | The originating business unit (the carwash outlet; UUID as string) — the partition key |

**Avro schema**

```json
{
  "type": "record",
  "name": "MetricPublished",
  "namespace": "id.co.nativeapp.events.carwash",
  "doc": "Emitted by a vertical (carwash-service) when an operational metric is produced — the per-employee/shift/outlet metrics employee-service consumes for variable pay / commission. Each vertical declares its metric contract: which metric_keys it emits at which grains (employee | shift | outlet); the consumer (employee side) rejects a commission rule requesting a grain the vertical cannot emit. carwash emits wash_count (a count) and upsell_amount (minor units of the wash currency) at the outlet grain. Key fields per ARCHITECTURE.md §5: metric_key, period, grain, subject_id, value, source_business_id.",
  "fields": [
    {"name": "metric_key", "type": "string", "doc": "The metric this measures, e.g. wash_count | upsell_amount."},
    {"name": "period", "type": "string", "doc": "The period the metric covers (YYYY-MM-DD for a daily metric)."},
    {"name": "grain", "type": "string", "doc": "The aggregation grain this metric is at: employee | shift | outlet."},
    {"name": "subject_id", "type": "string", "doc": "The id of the grain subject (UUID as string) — the outlet/employee/shift the value is for."},
    {"name": "value", "type": "long", "doc": "The metric value as a long: a count for wash_count, minor units of the wash currency for upsell_amount. Never a float."},
    {"name": "source_business_id", "type": "string", "doc": "The carwash outlet (business unit, UUID as string) the metric originated from; also the Kafka partition key / outbox aggregate id."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default (e.g. an
optional `unit` as `["null","string"]` with `default: null`). Never add a required field without a
default, never remove or rename a field, never change a field's type. The contract test
(`MetricPublishedContractTest`) enforces this — it asserts the schema is backward-compatible with
itself and with an added-optional-field variant, and rejects a new required field without a default.

### `EntitlementGranted`

Emitted by entitlement-service when a company is granted a module — either as part of
the **default set seeded on `CompanyCreated`** (a new company is granted its base
modules), or via an **explicit grant** (`POST /api/v1/entitlements`). One event per
grant.

- **Producer:** `entitlement-service`
- **Consumers:** the `shell` (decides what to render), and **all services** (a vertical /
  employee-service refreshes its local entitlement view). The `entitlement-check` shared
  library's **Redis cache is invalidated** by this event (the company's cached view is
  dropped, so the next entitled? check re-reads the source of truth).
- **Aggregate type / partition key:** `entitlement` / `company_id`
- **Outbox `event_type`:** `EntitlementGranted`
- **Schema:** `services/entitlement-service/src/main/resources/avro/EntitlementGranted.avsc`
- **Full name:** `id.co.nativeapp.events.entitlement.EntitlementGranted`

**Key fields** (ARCHITECTURE.md §5: `company_id`, `module_key`)

| Field | Avro type | Meaning |
|---|---|---|
| `company_id` | `string` | The owning tenant / company id (UUID as string); the partition key |
| `module_key` | `string` | The module granted (e.g. `restaurant`, `carwash`, `laundromat`, `hr`, `finance`) |
| `granted_at` | `long` (`timestamp-millis`) | When the grant took effect, epoch millis UTC |

**Avro schema**

```json
{
  "type": "record",
  "name": "EntitlementGranted",
  "namespace": "id.co.nativeapp.events.entitlement",
  "doc": "Emitted by entitlement-service when a company is granted a module — either as part of the DEFAULT set seeded on CompanyCreated, or via an explicit grant on POST /api/v1/entitlements. Consumed by the shell and all services; the entitlement-check Redis cache is invalidated by it. Key fields per ARCHITECTURE.md §5: company_id, module_key.",
  "fields": [
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string); also the Kafka partition key."},
    {"name": "module_key", "type": "string", "doc": "The module the company was granted (e.g. restaurant, carwash, laundromat, hr, finance)."},
    {"name": "granted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the grant took effect, epoch millis (UTC)."}
  ]
}
```

**Compatibility.** Backward-compatible only (add optional fields with a default; never a
new required field without a default, never remove/rename/retype). Enforced by
`EntitlementEventContractTest` (parse + `AvroSerde` round-trip + back-compat for-change /
against-break).

### `EntitlementRevoked`

Emitted by entitlement-service when a company's module entitlement is revoked
(`DELETE /api/v1/entitlements/{moduleKey}`). The `tenant_entitlement` row's status flips
to `REVOKED` (the row is kept, not deleted), and one event is emitted.

- **Producer:** `entitlement-service`
- **Consumers:** the `shell` (stop rendering the module) and **all services** (drop the
  local entitlement view). The `entitlement-check` Redis cache is **invalidated** by it.
- **Aggregate type / partition key:** `entitlement` / `company_id`
- **Outbox `event_type`:** `EntitlementRevoked`
- **Schema:** `services/entitlement-service/src/main/resources/avro/EntitlementRevoked.avsc`
- **Full name:** `id.co.nativeapp.events.entitlement.EntitlementRevoked`

**Key fields** (ARCHITECTURE.md §5: `company_id`, `module_key`)

| Field | Avro type | Meaning |
|---|---|---|
| `company_id` | `string` | The owning tenant / company id (UUID as string); the partition key |
| `module_key` | `string` | The module whose entitlement was revoked |
| `revoked_at` | `long` (`timestamp-millis`) | When the revoke took effect, epoch millis UTC |

**Avro schema**

```json
{
  "type": "record",
  "name": "EntitlementRevoked",
  "namespace": "id.co.nativeapp.events.entitlement",
  "doc": "Emitted by entitlement-service when a company's module entitlement is revoked via DELETE /api/v1/entitlements/{moduleKey}. Consumed by the shell and all services; the entitlement-check Redis cache is invalidated by it. Key fields per ARCHITECTURE.md §5: company_id, module_key.",
  "fields": [
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string); also the Kafka partition key."},
    {"name": "module_key", "type": "string", "doc": "The module whose entitlement was revoked."},
    {"name": "revoked_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the revoke took effect, epoch millis (UTC)."}
  ]
}
```

**Compatibility.** Backward-compatible only, enforced by `EntitlementEventContractTest`.

### `CompanyCreated` — entitlement-service consumer view

entitlement-service **consumes** the org-service `CompanyCreated` (the producer contract
is documented above) to seed a new company's **default entitlements**. It keeps its own
**consumer copy** of the schema at
`services/entitlement-service/src/main/resources/avro/CompanyCreated.avsc` (full name
`id.co.nativeapp.events.org.CompanyCreated`), reads the outbox payload as **raw Avro
bytes** via `libs/events AvroSerde` (no Schema Registry serde), and dedupes by the event
UUID (`ProcessedEventStore`) so a re-delivery never double-grants. On a new company it
grants the configured **default module set** (e.g. `restaurant, carwash, laundromat, hr,
finance`), persisting `tenant_entitlement` rows and emitting one `EntitlementGranted` per
grant — all in one transaction inside the new company's tenant scope (so RLS applies). The
`CompanyCreatedContractTest` asserts the consumer copy stays backward-compatible with the
producer schema (rule 7).

### `EmployeeChanged`

Emitted by employee-service (#19 — the HR records-only slice) when an `employee` is **created** or
its record fields **change** (name, ptkp_status, PII, or status). Consumed by the verticals to update
their local staff read model.

- **Producer:** `employee-service`
- **Consumers:** the verticals (local staff read model).
- **Aggregate type / partition key:** `employee` / `employee_id`
- **Outbox `event_type`:** `EmployeeChanged`
- **Schema:** `services/employee-service/src/main/resources/avro/EmployeeChanged.avsc`
- **Full name:** `id.co.nativeapp.events.employee.EmployeeChanged`

**No PII (rule 6).** The event carries only the non-PII identity + status: `employee_id`,
`company_id`, `status`. It NEVER carries the name, NIK, or bank account — those are column-encrypted
at rest and never cross a service boundary. A consumer that needs more reads its own slice.

**Key fields** (ARCHITECTURE.md §5: `employee_id`, `company_id`, `status`)

| Field | Avro type | Meaning |
|---|---|---|
| `employee_id` | `string` | The employee aggregate id (UUID as string); the partition key |
| `company_id` | `string` | The owning tenant / company id (UUID as string) |
| `status` | `string` | The employee's employment status: `ACTIVE` \| `INACTIVE` |

**Avro schema**

```json
{
  "type": "record",
  "name": "EmployeeChanged",
  "namespace": "id.co.nativeapp.events.employee",
  "doc": "Emitted by employee-service when an employee is created or its record fields change; consumed by the verticals (local staff read model). Key fields per ARCHITECTURE.md §5: employee_id, company_id, status. NO PII is carried — never the name, NIK, or bank account (rule 6).",
  "fields": [
    {"name": "employee_id", "type": "string", "doc": "The employee aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string)."},
    {"name": "status", "type": "string", "doc": "The employee's employment status: ACTIVE | INACTIVE."}
  ]
}
```

**Compatibility.** Backward-compatible only (add optional fields with a default; never a new required
field without a default, never remove/rename/retype). Enforced by `EmployeeChangedContractTest`
(parse + `AvroSerde` round-trip + back-compat for-change / against-break), which also pins that NO PII
field is present.

### `AssignmentChanged`

Emitted by employee-service (#19) when an `assignment` is **created** (or changes). An employee holds
multiple concurrent assignments; org/team/manager live on the assignment, not the employee
(ARCHITECTURE.md §2). Consumed by the verticals (local staff read model) and finance (labor-cost
dimensions later).

- **Producer:** `employee-service`
- **Consumers:** the verticals (local staff read model), `finance-service` (labor-cost dimensions).
- **Aggregate type / partition key:** `assignment` / `assignment_id`
- **Outbox `event_type`:** `AssignmentChanged`
- **Schema:** `services/employee-service/src/main/resources/avro/AssignmentChanged.avsc`
- **Full name:** `id.co.nativeapp.events.employee.AssignmentChanged`

**No PII (rule 6).** Only the assignment dimensions are carried. `reporting_to` is a nullable union
(an assignment may have no manager). `effective_from`/`effective_to` are Avro `date` logical-type
ints (epoch day); an open-ended assignment uses the far-future `9999-12-31` sentinel, never null.

**Key fields** (ARCHITECTURE.md §5: `employee_id`, `org_unit_id`, `reporting_to`, `effective_from/to`)

| Field | Avro type | Meaning |
|---|---|---|
| `assignment_id` | `string` | The assignment aggregate id (UUID as string); the partition key |
| `employee_id` | `string` | The employee this assignment is for (UUID as string) |
| `company_id` | `string` | The owning tenant / company id (UUID as string) |
| `org_unit_id` | `string` | The org unit this assignment is to (UUID as string) |
| `reporting_to` | `["null","string"]` (default `null`) | The manager (employee id), or null |
| `role` | `string` | The role held in this assignment |
| `effective_from` | `int` (`date`) | The date the assignment becomes effective (epoch day) |
| `effective_to` | `int` (`date`) | The date it ceases to be effective; `9999-12-31` when open-ended |

**Avro schema**

```json
{
  "type": "record",
  "name": "AssignmentChanged",
  "namespace": "id.co.nativeapp.events.employee",
  "doc": "Emitted by employee-service when an assignment is created or changes; consumed by the verticals and finance. Key fields per ARCHITECTURE.md §5: employee_id, org_unit_id, reporting_to, effective_from/to. NO PII (rule 6). reporting_to is a nullable union; effective dates are date logical-type ints (epoch day), open-ended = 9999-12-31.",
  "fields": [
    {"name": "assignment_id", "type": "string", "doc": "The assignment aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "employee_id", "type": "string", "doc": "The employee this assignment is for (UUID as string)."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string)."},
    {"name": "org_unit_id", "type": "string", "doc": "The org unit this assignment is to (UUID as string)."},
    {"name": "reporting_to", "type": ["null", "string"], "default": null, "doc": "The manager (employee id, UUID as string) this assignment reports to, or null."},
    {"name": "role", "type": "string", "doc": "The role held in this assignment."},
    {"name": "effective_from", "type": {"type": "int", "logicalType": "date"}, "doc": "The date the assignment becomes effective (epoch day)."},
    {"name": "effective_to", "type": {"type": "int", "logicalType": "date"}, "doc": "The date the assignment ceases to be effective (epoch day); 9999-12-31 sentinel when open-ended."}
  ]
}
```

**Compatibility.** Backward-compatible only, enforced by `AssignmentChangedContractTest`.

### `OrgUnitCreated` / `OrgUnitChanged` — employee-service consumer view

employee-service **consumes** the org-service `OrgUnitCreated` / `OrgUnitChanged` (the producer
contracts are documented above) into a **local org read model** (`org_unit_id -> {company_id,
legal_employer_id, type, active}`) — the projection the same-legal-employer assignment invariant
(ARCHITECTURE.md §2) is checked against (rule 2 — a cached read model, never a sync call). It keeps
its own **consumer copies** of the schemas at
`services/employee-service/src/main/resources/avro/OrgUnitCreated.avsc` and `.../OrgUnitChanged.avsc`
(full names `id.co.nativeapp.events.org.OrgUnitCreated` / `.OrgUnitChanged`), reads the outbox
payload as **raw Avro bytes** via `libs/events AvroSerde` (no Schema Registry serde), and dedupes by
the event UUID (`ProcessedEventStore`) so a re-delivery never double-applies. The projection write
runs inside a `TenantContext` scope bound to the event's `company_id`, so RLS applies. The
`OrgUnitConsumerContractTest` asserts the consumer copies stay backward-compatible with the producer
schemas (rule 7). On creating an assignment, the target org_unit's `legal_employer_id` is resolved
from this read model and a concurrent assignment under a DIFFERENT legal employer is rejected
(`409`).

### `OrgUnitCreated` / `OrgUnitChanged` — finance-service consumer view

finance-service **consumes** the org-service `OrgUnitCreated` / `OrgUnitChanged` (the producer
contracts are documented above) into a **local org-unit name cache** (`org_unit_ref` table: `org_unit_id
-> {company_id, type, parent_id, name, active}`) — the reference the `GET /api/v1/pnl/outlets`
endpoint LEFT-JOINs to resolve outlet display names without a synchronous call to org-service (rule
2). It reads the schemas from `libs/contracts` on the classpath (`avro/OrgUnitCreated.avsc` /
`avro/OrgUnitChanged.avsc`, the single source of truth — ADR 0003), reads the outbox payload as
**raw Avro bytes** via `libs/events AvroSerde` (no Schema Registry serde), and dedupes by the event
UUID (`ProcessedEventStore`) so a re-delivery never double-applies. The upsert runs inside a
`TenantContext` scope bound to the event's `company_id` (RLS applies — rule 5). Names may lag by up
to one event-delivery cycle after a rename; callers must tolerate `outletName: null` (not-yet-known
is not an error — the revenue row is returned regardless). The `OrgUnitConsumerContractTest`
(finance) asserts both schemas stay backward-compatible with the producer schemas (rule 7).

**Topics consumed:** `OrgUnitCreated`, `OrgUnitChanged`.

### `EntitlementGranted` / `EntitlementRevoked` — carwash-service consumer view

carwash-service (#20 — the 2nd vertical) **consumes** the entitlement-service
`EntitlementGranted` / `EntitlementRevoked` (the producer contracts are documented above) into a
**local entitlement projection** (`(company_id, module_key) -> entitled`) and, on apply, **invalidates
the `entitlement-check` Redis cache** for that company. It keeps its own **consumer copies** of the
schemas at `services/carwash-service/src/main/resources/avro/EntitlementGranted.avsc` and
`.../EntitlementRevoked.avsc` (same full names), reads the outbox payload as **raw Avro bytes** via
`libs/events AvroSerde` (no Schema Registry serde), and dedupes by the event UUID
(`ProcessedEventStore`) so a re-delivery never double-applies. The projection write runs inside a
`TenantContext` scope bound to the event's `company_id`, so RLS applies. This projection backs the
DB-backed `EntitlementLoader` the shared `libs/entitlement-check` cache consults on a miss, so the
**record-wash gate** (`POST /api/v1/washes` → `403` when not entitled to `carwash`) reflects a grant
/ revoke promptly. The `EntitlementConsumerContractTest` asserts the consumer copies stay
backward-compatible with the producer schemas (rule 7).

### `EntitlementGranted` / `EntitlementRevoked` — restaurant-service consumer view

restaurant-service (Phase 6, ADR 0029 — self-order QR) **consumes** the entitlement-service
`EntitlementGranted` / `EntitlementRevoked` (the producer contracts are documented above) into a
**local entitlement projection** (`(company_id, module_key) -> entitled`) and, on apply,
**invalidates the `entitlement-check` Redis cache** for that company — mirrors carwash-service's /
barbershop-service's identical consumer exactly. Unlike those two, restaurant-service does **not**
keep a per-service `.avsc` copy under `src/main/resources/avro/` — it resolves the schema straight
from the shared `libs/contracts` classpath resource (`avro/EntitlementGranted.avsc` / `.../
EntitlementRevoked.avsc`, ADR 0003's single source of truth), since restaurant-service already
depends on `libs/contracts`. It reads the outbox payload as **raw Avro bytes** via `libs/events
AvroSerde` (no Schema Registry serde) and dedupes by the event UUID (`ProcessedEventStore`) so a
re-delivery never double-applies; the projection write runs inside a `TenantContext` scope bound to
the event's `company_id` (RLS applies — rule 5). This projection backs the DB-backed
`ProjectionEntitlementLoader` the shared `libs/entitlement-check` cache consults on a miss, so the
**self-order-create gate** (`POST /api/v1/self-order/orders` → `403` when not entitled to
`self_order`) reflects a grant/revoke promptly. `module_catalog` gained the `self_order` module in
entitlement-service's `V5__self_order_module.sql`.

**Topics consumed:** `EntitlementGranted`, `EntitlementRevoked`.

### `EmployeeChanged` / `AssignmentChanged` — carwash-service consumer view

carwash-service (#20) also **consumes** the employee-service `EmployeeChanged` / `AssignmentChanged`
into a **local staff read model** (`employee_id -> {org_unit_id, active}`) so the vertical knows its
staff (rule 2 — a cached read model, never a sync call). It keeps its own **consumer copies** of the
schemas at `services/carwash-service/src/main/resources/avro/EmployeeChanged.avsc` and
`.../AssignmentChanged.avsc` (same full names), reads the outbox payload as **raw Avro bytes** via
`libs/events AvroSerde`, dedupes by the event UUID (`ProcessedEventStore`), and writes inside a
`TenantContext` scope bound to the event's `company_id` (RLS applies). **NO PII** is projected — the
events carry only `employee_id` / `company_id` / `status` and the assignment dimensions (rule 6). The
`StaffConsumerContractTest` asserts the consumer copies stay backward-compatible with the producer
schemas (rule 7).

### `ConsolidationClosed`

Emitted by finance-service when a consolidation close completes — the period is locked, intercompany
matching has run, and the result is final (ARCHITECTURE.md §4: "never present a mid-flight
consolidation as final"). It is the trigger notification-service consumes (#22) to create + deliver a
"consolidation closed for &lt;period&gt;" notice. **The finance PRODUCER path is now LIVE (P3d SEAM
4a — THE PRODUCERS).**

- **Producer:** `finance-service`. Emitted in **two** places, both via the transactional outbox (rule
  3), atomic with the close, and ONLY on a real CLOSED close:
  1. a **WITHIN-COMPANY close** (`WithinCompanyCloseWriter`) → `ConsolidationClosed(company_id = the
     company, group_id = NULL, period)`. The nullable `group_id` is what distinguishes this kind: the
     company finalised its own period.
  2. a **GROUP close** (`GroupCloseWriter`, ONLY when the `consolidation_summary` reaches `CLOSED` —
     never a HELD `PENDING_MEMBERS` / `MEMBER_TRIAL_BALANCE_UNBALANCED` / `INTERCOMPANY_UNRECONCILED`
     / `PENDING_MEMBERS_WARMING_UP` / `SUPERSEDED` state) → `ConsolidationClosed(company_id = the
     group's lead, group_id = G, period)`. **Idempotent:** the emission is claimed in the
     `ProcessedEventStore` under a deterministic `(group, period, close_run_seq)` key
     (`ConsolidationEntryIds.forConsolidationClosedEmit`), so a re-delivery at the SAME seq never
     double-emits, while a re-close at a HIGHER `close_run_seq` legitimately emits a FRESH event.
- **Consumers:** the `shell` (refresh the consolidated dashboard), and **`notification-service`**
  (create + deliver a notification) — **LIVE (notification consumer #22)**.
- **Aggregate type / partition key:** `consolidation` (within-company; partition key `company_id`) /
  `consolidation` with partition key `group_id` (group close).
- **Outbox `event_type`:** `ConsolidationClosed`
- **Schema (producer, source of truth):** `services/finance-service/src/main/resources/avro/ConsolidationClosed.avsc` (`ConsolidationClosedSchema` parses + builds it). **LIVE.**
- **Schema (notification consumer copy):** `services/notification-service/src/main/resources/avro/ConsolidationClosed.avsc` — notification owns its own consumer view of the contract; the notification `ConsolidationClosedContractTest` asserts the copy stays backward-compatible with the producer schema (rule 7). The producer schema is byte-compatible with this consumer copy (same three fields, the nullable `group_id` union with `default null`), so the existing contract test stays satisfied. The notification consumer reads the outbox payload as **raw Avro bytes** via `libs/events AvroSerde` (no Schema Registry serde), deduping by the event UUID (`ProcessedEventStore`) so a re-delivery never creates a duplicate notification.
- **Full name:** `id.co.nativeapp.events.finance.ConsolidationClosed`

A close is scoped to a single company (within-company consolidation) **or** a group (group
consolidation + elimination). `company_id` is always present and is the tenant the notification is
created under; `group_id` is a nullable union, set only for a group-level close (ARCHITECTURE.md §5:
`company_id (or group_id), period`).

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `company_id` | `string` | The owning tenant / company id (UUID as string); the partition key. For a group close, the group's lead/owner company (the tenant the notification belongs to) |
| `group_id` | `["null","string"]` (default `null`) | The consolidation group id (UUID as string) for a group close, else null |
| `period` | `string` | The accounting period that was closed (e.g. `YYYY-MM`) |

**Avro schema**

```json
{
  "type": "record",
  "name": "ConsolidationClosed",
  "namespace": "id.co.nativeapp.events.finance",
  "doc": "Emitted by finance-service when a consolidation close completes for a company (or a group) and a period; consumed by the shell (refresh the dashboard) and notification-service (#22 — create + deliver a 'consolidation closed for <period>' notification). Key fields per ARCHITECTURE.md §5: company_id (or group_id), period.",
  "fields": [
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string); also the Kafka partition key. For a group close, the group's lead/owner company id (the tenant the notification belongs to)."},
    {"name": "group_id", "type": ["null", "string"], "default": null, "doc": "The consolidation group id (UUID as string) for a group-level close; null for a within-company close."},
    {"name": "period", "type": "string", "doc": "The accounting period that was closed (e.g. YYYY-MM)."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default. Never
add a required field without a default, never remove or rename a field, never change a field's type.
The contract test (`ConsolidationClosedContractTest`, notification-service) enforces this — it parses
the consumer copy, round-trips a `GenericRecord` through `AvroSerde`, asserts the copy is
backward-compatible with the producer schema, and rejects a new required field without a default (the
triad).

### `DeliveryReceipt`

Emitted by notification-service (#22) when a notification has been DELIVERED through a transport — or
has FAILED delivery (a delivery failure is **recorded, not swallowed**, so a FAILED receipt is emitted
too). Published via the transactional outbox (rule 3) in the **same transaction** as the
`notification` + `delivery_receipt` rows, so the receipt event commits atomically with the receipt it
reports.

- **Producer:** `notification-service`
- **Consumers:** downstream sinks that track notification outcomes (an audit/observability sink, a
  re-send policy) — none wired yet; the event is published for when they arrive.
- **Aggregate type / partition key:** `notification` / `notification_id`
- **Outbox `event_type`:** `DeliveryReceipt`
- **Schema:** `services/notification-service/src/main/resources/avro/DeliveryReceipt.avsc`
- **Full name:** `id.co.nativeapp.events.notification.DeliveryReceipt`

**NO PII (rule 6).** The event carries only the ids, the channel, the outcome status, and the
transport's `provider_ref` (a synthetic id for the stub). It never carries the recipient address, the
subject, or the body. `delivered_at` is epoch millis (UTC) via the Avro `timestamp-millis` logical
type; `provider_ref` is a nullable union (a failure may carry none).

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `notification_id` | `string` | The notification this receipt is for (UUID as string); the partition key |
| `company_id` | `string` | The owning tenant / company id (UUID as string) |
| `channel` | `string` | The delivery channel: `EMAIL` \| `PUSH` |
| `status` | `string` | The delivery outcome: `DELIVERED` \| `FAILED` |
| `provider_ref` | `["null","string"]` (default `null`) | The transport reference (synthetic for the stub), or null |
| `delivered_at` | `long` (`timestamp-millis`) | When the delivery attempt completed, epoch millis UTC |

**Avro schema**

```json
{
  "type": "record",
  "name": "DeliveryReceipt",
  "namespace": "id.co.nativeapp.events.notification",
  "doc": "Emitted by notification-service when a notification has been DELIVERED (or has FAILED delivery) through a transport. Published via the transactional outbox (rule 3) in the SAME transaction as the notification + delivery_receipt rows. A delivery failure is RECORDED, not swallowed — a FAILED receipt is emitted too. NO PII or secret is ever carried (rule 6): only the ids, the channel, the outcome status, and the transport's provider_ref.",
  "fields": [
    {"name": "notification_id", "type": "string", "doc": "The notification this receipt is for (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string)."},
    {"name": "channel", "type": "string", "doc": "The delivery channel: EMAIL | PUSH."},
    {"name": "status", "type": "string", "doc": "The delivery outcome: DELIVERED | FAILED."},
    {"name": "provider_ref", "type": ["null", "string"], "default": null, "doc": "The transport's reference for the delivery attempt (a synthetic id for the stub), or null when the transport returned none."},
    {"name": "delivered_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the delivery attempt completed, epoch millis (UTC)."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default (e.g. an
optional `attempt_count`). Never add a required field without a default, never remove or rename a
field, never change a field's type. The contract test (`DeliveryReceiptContractTest`,
notification-service) enforces this — it parses the schema, round-trips a `GenericRecord` through
`AvroSerde` (including a null `provider_ref`), accepts an added optional field, and rejects a new
required field without a default (the triad).

### `PayrollPosted`

Emitted by **employee-service** (#23, the Phase-3 payroll engine) when a `payroll_run` transitions
`CALCULATED -> POSTED`. Consumed by **finance-service** (payroll consolidation). Published via the
**transactional outbox** (rule 3) in the SAME transaction that flips the run to `POSTED`; only a
`CALCULATED -> POSTED` transition emits, and a UNIQUE `(company_id, period, run_seq)` guards against a
double post, so a retried post cannot double-emit.

- **Producer aggregate:** `payroll_run` (the run id is the Kafka partition key).
- **Outbox `event_type`:** `PayrollPosted`
- **Schema:** `libs/contracts/src/main/resources/avro/PayrollPosted.avsc` (single source, ADR 0003)
- **Full name:** `id.co.nativeapp.events.employee.PayrollPosted`

**NO PII (rule 6).** Company-level **totals only** — no per-person amounts, no salary, no NIK/bank.
Carries the **frozen `rule_versions` set** (HR-7 reproducibility) and the runtime
`uses_illustrative_rules` flag so a run computed against `ILLUSTRATIVE_PLACEHOLDER` statutory figures
is loud on the wire and cannot be mistaken for an official-figure run. A corrected re-run is a **new
`run_seq`** → a second `PayrollPosted` for the period that **supersedes** the prior run (finance
handles supersession, not double-counting two runs for one period).

**Avro schema**

```json
{
  "type": "record",
  "name": "PayrollPosted",
  "namespace": "id.co.nativeapp.events.employee",
  "fields": [
    {"name": "payroll_run_id", "type": "string"},
    {"name": "company_id", "type": "string"},
    {"name": "period", "type": "string"},
    {"name": "base_currency", "type": "string"},
    {"name": "gross_total_minor", "type": "long"},
    {"name": "employee_deduction_total_minor", "type": "long"},
    {"name": "employer_contribution_total_minor", "type": "long"},
    {"name": "net_total_minor", "type": "long"},
    {"name": "rule_versions", "type": {"type": "array", "items": {"type": "record", "name": "RuleVersion", "fields": [
      {"name": "rule_key", "type": "string"},
      {"name": "rule_version", "type": "string"}
    ]}}},
    {"name": "run_seq", "type": "int", "default": 1},
    {"name": "uses_illustrative_rules", "type": "boolean"},
    {"name": "posted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
    {"name": "run_type", "type": "string", "default": "REGULAR"}
  ]
}
```

**Compatibility.** Backward-compatible evolution only: `uses_illustrative_rules` is present from v1;
`run_seq` is added **backward-compatibly** (`default: 1`, so an old-producer record with no `run_seq`
reads as the first run); `run_type` is added **backward-compatibly** (`default: "REGULAR"`, ADR 0032,
Track P phase P4 — an old-producer record with no `run_type` reads as `REGULAR`); future fields are
added as a union-with-default. The producer **populates `run_seq` from `payroll_run.run_seq`** on the
wire (a corrected re-run carries `run_seq=2`) and **stamps `run_type` as the constant `REGULAR`**
until Track P phase P8 (THR, ADR 0034) gives `payroll_run` a real `run_type` column — so finance reads
the real sequence, not the default 1, and supersession actually fires, now scoped to `(company_id,
period, run_type, run_seq)`. The contract test (`PayrollPostedContractTest`, both services) enforces
the triad (parse + `AvroSerde` round-trip + add-optional compatible / new-required broken), that a
`run_seq=2` record round-trips as `2`, and that a pre-ADR-0032 record with no `run_type` reads as
`REGULAR`.

**Finance CONSUMER view (#23; ADR 0032 P4 for `run_type`).** finance-service consumes `PayrollPosted`
as the run-level **control/announcement** — it **produces NO ledger posting**. It is used only for: (a)
**reconciliation** — the labor control total (`employer_contribution_total_minor + gross_total_minor`,
the employer-borne base, in minor units, same currency) is compared against the running sum of the
run's `LaborCostAllocated` buckets on the `payroll_run_ledger` control row keyed on `(company_id,
period, run_type, run_seq)`: **match → `RECONCILED`**, **mismatch → `RECONCILE_FAILED`** (loud;
postings stay on the books, the period is held back from being presented as final — never silently
accept a partial run); (b) recording the run-level **illustrative flag**; (c) the **supersession**
trigger / run-state machine — the per-`(company, period, run_type)` advisory lock and the
supersession-scan queries are shared with `LaborCostAllocated`'s consumer AND `PayrollLiabilitiesPosted`'s
(below), so all three payroll consumers serialize against each other on the same control row. The
consumer is **idempotent** (dedupe by the `id`-header event UUID via `ProcessedEventStore`, inside the
reconciliation `@Transactional`), binds the tenant from the event `company_id` (RLS — rule 5), and
fails closed to `PayrollPosted.DLT` on a missing/non-UUID `id` header or an undecodable payload
(`PayrollPostedDecodeException`, non-retryable). `PayrollPostedContractTest` (both services) asserts
back-compat.

### `LaborCostAllocated`

Emitted by **employee-service** (#23) per **(outlet_org_unit_id, gl_account) bucket** when a
`payroll_run` posts. Consumed by **finance-service** (dimensional ledger). Published via the
**transactional outbox** (rule 3) in the SAME transaction that flips the run to `POSTED`.

- **Producer aggregate:** `payroll_run` (the run id is the Kafka partition key).
- **Outbox `event_type`:** `LaborCostAllocated`
- **Schema:** `libs/contracts/src/main/resources/avro/LaborCostAllocated.avsc` (single source, ADR 0003)
- **Full name:** `id.co.nativeapp.events.employee.LaborCostAllocated`

**NO PII (rule 6).** One event **per outlet/GL bucket, AGGREGATED across employees** so no individual
salary is derivable. **NOTE / open risk:** ARCHITECTURE.md §5 lists `employee_id` on this event, but
a per-`(employee, outlet, gl)` amount effectively leaks individual labor cost ≈ salary; `employee_id`
is therefore **DROPPED** here by design decision (finance gets outlet/GL granularity, which is what
the dimensional ledger needs). This diverges from the §5 starter field list and needs finance
sign-off. The exact-sum allocation invariant (`sum(allocations) == run labor total`, in minor units,
largest-remainder residual) is asserted before the run may post.

**UNALLOCATED suspense bucket.** An employee with **no outlet assignment** in the period (on leave /
between assignments) has their employer labor cost routed to an explicit suspense bucket — `outlet_id`
is the all-zeros sentinel UUID, `gl_account` is `9999-UNALLOCATED-LABOR`, and the boolean
`unallocated` field is `true`. This keeps the exact-sum invariant holding (the cost is never silently
dropped) and lets finance clear the suspense rather than mistaking it for a real outlet cost. The
field was added **backward-compatibly** with a `default: false`, so an old reader simply ignores it.

**Avro schema**

```json
{
  "type": "record",
  "name": "LaborCostAllocated",
  "namespace": "id.co.nativeapp.events.employee",
  "fields": [
    {"name": "payroll_run_id", "type": "string"},
    {"name": "company_id", "type": "string"},
    {"name": "period", "type": "string"},
    {"name": "outlet_id", "type": "string"},
    {"name": "gl_account", "type": "string"},
    {"name": "amount_minor", "type": "long"},
    {"name": "currency", "type": "string"},
    {"name": "run_seq", "type": "int", "default": 1},
    {"name": "uses_illustrative_rules", "type": "boolean"},
    {"name": "unallocated", "type": "boolean", "default": false},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
    {"name": "run_type", "type": "string", "default": "REGULAR"}
  ]
}
```

**Compatibility.** Backward-compatible evolution only (the `unallocated`, `run_seq`, and `run_type`
fields all carry defaults — `run_seq` `default: 1`, `run_type` `default: "REGULAR"`, ADR 0032 Track P
phase P4). The producer **populates `run_seq` from `payroll_run.run_seq`** on the wire (a corrected
re-run carries `run_seq=2`) and **stamps `run_type` as the constant `REGULAR`** until Track P phase P8
(THR, ADR 0034), so finance reads the real sequence — not the default 1 — and supersession actually
fires, now scoped to `(company_id, period, run_type, run_seq)`. The contract test
(`LaborCostAllocatedContractTest`, both services) enforces the triad, that a `run_seq=2` record
round-trips as `2`, and that a pre-ADR-0032 record with no `run_type` reads as `REGULAR`.

**Finance CONSUMER view (#23).** finance-service consumes each `LaborCostAllocated` bucket as exactly
one **EXPENSE `ledger_posting`** (the labor cost is genuinely an expense). The posting's
`business_id = outlet_id`, `period` = the event's authoritative **run period** (NOT derived from
`occurred_at`; `occurred_at` drives only the effective `mapping_rule` version), `amount = Money`
(minor units — never a float, rule 8). The `gl_account` is treated as a **HINT**: finance **RE-RESOLVES**
the canonical account via its own `mapping_rule` (CQRS, resolve-on-write — the ledger owns its chart of
accounts), exactly like `ExpenseRecorded.gl_hint`. An unrecognised hint **fails safe** to the expense
suspense `9999` (money never dropped — HR-3); the **UNALLOCATED** bucket (`outlet_id` = all-zeros
sentinel, `gl_account = 9999-UNALLOCATED-LABOR`, `unallocated = true`) is routed to the explicit,
visible **`6900` Unallocated-Labor-Clearing** account (distinct from the general suspense) with the
`unallocated` flag stamped on the posting. Each PRIMARY posting moves the consolidated P&L expense leg
up (`PnlReadModelWriter.addExpense`), carrying `uses_illustrative_rules` **sticky-OR** onto the
`consolidated_pnl` row. **Supersession** (a higher `run_seq` of the same `run_type` for the same
`(company_id, period)` — ADR 0032 Track P phase P4 added `run_type` to the scope) is **append-only**:
finance posts one **REVERSAL** contra per prior PRIMARY posting (amount negated, a deterministic
synthetic `source_event_id`, `posting_role = REVERSAL`) and flips the prior `payroll_run_ledger` row's
`state` to `SUPERSEDED` — the ledger never mutates and the supersession is itself idempotent under
re-delivery. The consumer is **idempotent** (dedupe by the `id`-header event UUID;
`ledger_posting.source_event_id` UNIQUE backstop), binds the tenant from the event `company_id` (RLS),
and fails closed to `LaborCostAllocated.DLT` on a bad `id` header or an undecodable payload
(`LaborCostAllocatedDecodeException`, non-retryable). `LaborCostAllocatedContractTest` (both services)
asserts back-compat. **`employee_id` is intentionally omitted** — finance needs only `(outlet,
gl_account)` granularity; a per-employee amount would leak individual labor cost ≈ salary (rule 6).
This is the recorded **finance sign-off** (see ARCHITECTURE.md §5), and the **k=1**
single-occupant-outlet residual (a one-employee outlet's bucket equals that person's cost) is an
accepted, RLS-/role-gated residual, not mitigated by suppression.

### `PayrollLiabilitiesPosted`

Emitted by **employee-service** (ADR 0032, Track P phase P4) when a `payroll_run` transitions
`CALCULATED -> POSTED` — the **THIRD** event of the `post()` transaction, after `PayrollPosted` and
every `LaborCostAllocated` bucket, in the SAME outbox transaction (rule 3). Carries the run's total
employer-borne labor cost split into WHO it is owed to — net wages to employees, PPh21 to the tax
office, BPJS to the two BPJS bodies, any other deduction — so finance can finally book the LIABILITY
side of the money `LaborCostAllocated` already expensed, closing the #1 payroll-parity honesty gap
(`6900 LABOR_CLEARING` accumulated forever with nothing ever clearing it).

- **Producer aggregate:** `payroll_run` (the run id is the Kafka partition key).
- **Consumer:** `finance-service` (`labor` package: `PayrollLiabilityWriter`).
- **Outbox `event_type`:** `PayrollLiabilitiesPosted`
- **Schema:** `libs/contracts/src/main/resources/avro/PayrollLiabilitiesPosted.avsc` (single source, ADR 0003)
- **Full name:** `id.co.nativeapp.events.employee.PayrollLiabilitiesPosted`

**NO PII (rule 6).** Company-level bucket **TOTALS only** — no per-person amounts, no salary, no
NIK/bank, exactly like `PayrollPosted`/`LaborCostAllocated`.

**The producer asserts the accounting identity before writing anything**:
`employer_cost_total_minor == Σ(liability bucket amounts)` — `liabilities` already INCLUDES the
`NET_WAGES_PAYABLE` bucket (== `net_total_minor`), so it is never added a second time on top of the
buckets sum (an implementation bug that double-counted it this way was caught by the P4 test suite
before it shipped). Never emits an unbalanced set (HR-3; see ADR 0032 for the full derivation, incl.
why the identity holds structurally). **A bucket amount MAY be negative** — the December Art-17
true-up refund month (ADR 0031) can drive `PPH21_PAYABLE` negative; finance posts a negative bucket
as a Dr leg for its absolute value instead of a negative Cr leg (`JournalLine`'s single-sided,
strictly-positive invariant).

**Avro schema**

```json
{
  "type": "record",
  "name": "PayrollLiabilitiesPosted",
  "namespace": "id.co.nativeapp.events.employee",
  "fields": [
    {"name": "payroll_run_id", "type": "string"},
    {"name": "company_id", "type": "string"},
    {"name": "period", "type": "string"},
    {"name": "run_seq", "type": "int", "default": 1},
    {"name": "base_currency", "type": "string"},
    {"name": "employer_cost_total_minor", "type": "long"},
    {"name": "liabilities", "type": {"type": "array", "items": {"type": "record", "name": "PayrollLiabilityBucket", "fields": [
      {"name": "liability_role", "type": "string"},
      {"name": "amount_minor", "type": "long"}
    ]}}, "default": []},
    {"name": "uses_illustrative_rules", "type": "boolean"},
    {"name": "posted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
    {"name": "run_type", "type": "string", "default": "REGULAR"}
  ]
}
```

**`liability_role`** is one of `NET_WAGES_PAYABLE`, `PPH21_PAYABLE`, `BPJS_KES_PAYABLE` (Kesehatan
EE-withheld + ER-contribution together — both owed to the same BPJS body), `BPJS_TK_PAYABLE` (JHT +
JP, EE+ER, plus JKK/JKM ER-only — all owed to the same BPJS body), `OTHER_DEDUCTIONS_PAYABLE` (any
deduction line not named above, EMPLOYEE- or EMPLOYER-borne alike — e.g. a future custom component
such as a loan repayment). A zero-amount bucket is omitted by the producer.

**Compatibility.** A brand-new event (no prior version to be backward-compatible WITH); `run_seq`
and `run_type` both carry defaults from day one (mirroring the ADR 0032 addition to `PayrollPosted`/
`LaborCostAllocated` in the SAME phase) so a FUTURE additive field follows the same union-with-default
idiom. The contract test (`PayrollLiabilitiesPostedContractTest`, both services) enforces the triad
(parse + shape, `AvroSerde` round-trip incl. a negative bucket amount, add-optional compatible /
new-required broken).

**Finance CONSUMER view (ADR 0032, Track P phase P4).** finance-service books ONE ad-hoc balanced
`JournalEntry` per run — **not** a `posting_template` (the leg count is variable, 1–5 non-zero
buckets, and a bucket may sit on either side depending on sign): `Dr LABOR_CLEARING (6900)` for
`employer_cost_total_minor` / `Cr` one leg per positive bucket (a negative bucket instead `Dr`s its
resolved account for the absolute value). Each `liability_role` string resolves to an `AccountRole`
by name, then to a `chart_of_account` code via `role_account_map`; an unrecognised role string, or a
role with no effective mapping, **fails safe** to `SUSPENSE` with a logged WARN (money never dropped
— HR-3). The five liability accounts (`2610` PPh21 / `2620` BPJS-Kes / `2630` BPJS-TK / `2640` Net
Wages / `2690` Other) are ILLUSTRATIVE, SME-gated (V40) exactly like every other account seeded since
V13.

**Supersession** is tracked on its OWN `payroll_run_ledger.liability_state` column — INDEPENDENTLY of
the `state` column `LaborCostAllocated`'s consumer owns (ADR 0032 explains why sharing one column
would create a cross-writer coordination gap). A higher `run_seq` of the same `(company_id, period,
run_type)` reverses each prior run's ACTIVE liability entry (append-only, per-leg debit↔credit swap,
deterministic synthetic `source_event_id`) and flips the prior row's `liability_state` to
`SUPERSEDED`; an out-of-order lower-seq event that arrives after a higher-seq run's liability already
posted posts its own PRIMARY then immediately self-contras (mirrors `LaborCostAllocated`'s consumer
steps 2a/6 exactly). The SAME per-`(company, period, run_type)` advisory lock `PayrollPosted`'s and
`LaborCostAllocated`'s consumers take serializes all three writers against each other on the shared
control row. The consumer is **idempotent** (dedupe by the `id`-header event UUID via
`ProcessedEventStore`; `journal_entry.source_event_id` UNIQUE backstop), binds the tenant from the
event `company_id` (RLS), and fails closed to `PayrollLiabilitiesPosted.DLT` on a bad `id` header or
an undecodable payload (`PayrollLiabilitiesPostedDecodeException`, non-retryable).
`PayrollLiabilitiesPostedContractTest` (both services) asserts the triad.

**No settlement yet.** This event only RECOGNISES the liability; clearing it (a bank-file payment for
net wages, a tax-office remittance, a BPJS contribution payment) is Track P phase P5
(`payroll_settlement`).

### `PeriodSealed`

A vertical (carwash-service and the others) emits `PeriodSealed` when a business unit (outlet) has
sealed its operational data for a period — the **completeness gate** (ARCHITECTURE.md §4): a
`payroll_run` may only transition to `CALCULATING`/`POST` once **every expected source `business_id`
for the period has sealed** (no running on partial data). Consumed by **employee-service** (#23, the
gate) and **finance**.

- **Full name:** `id.co.nativeapp.events.<vertical>.PeriodSealed` (employee's consumer copy uses
  `id.co.nativeapp.events.carwash.PeriodSealed`).
- **Key fields** (EVENT-CATALOG): `company_id`, `business_id`, `period`.

**Avro schema** (employee-service consumer copy at
`services/employee-service/src/main/resources/avro/PeriodSealed.avsc`)

```json
{
  "type": "record",
  "name": "PeriodSealed",
  "namespace": "id.co.nativeapp.events.carwash",
  "fields": [
    {"name": "company_id", "type": "string"},
    {"name": "business_id", "type": "string"},
    {"name": "period", "type": "string"},
    {"name": "sealed_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
  ]
}
```

### `MetricPublished` / `PeriodSealed` — employee-service consumer view

employee-service (#23) **consumes** `MetricPublished` (variable-pay/commission inputs) and
`PeriodSealed` (the completeness gate). It keeps its own **consumer copies** of the schemas at
`services/employee-service/src/main/resources/avro/MetricPublished.avsc` and `.../PeriodSealed.avsc`,
reads the outbox payload as **raw Avro bytes** via `libs/events AvroSerde` (no Schema Registry
serde), and dedupes by the event UUID (`ProcessedEventStore`) so a re-delivery never double-applies
(rule 3). The projection write runs inside a `TenantContext` scope bound to the event's `company_id`
(RLS applies — rule 5): `MetricPublished` **accumulates** into `metric_input` (each event's `value`
is a delta added onto the natural-key row, not a replacement — see the delta-semantics note above);
`PER_METRIC_UNIT` and `PERCENT_OF_METRIC` earning rules read it (rule 2, never a sync call).
`PERCENT_OF_METRIC` powers own-sales commission: it sums the employee's `sales_amount` rows at the
`employee` grain (`subject_id` = the employee's `user_id`, i.e. their Keycloak `sub`) for the run
month and applies the rule's basis-point rate via the `Money` type. `PeriodSealed` projects into
`period_seal` (the gate reads it). A record without a valid `id` header (and, for `MetricPublished`,
a `company_id` header — the payload carries no `company_id`) fails closed to `<topic>.DLT`. The
contract tests (`PayrollConsumerContractTest`) assert the `MetricPublished` consumer copy stays
backward-compatible with the producer schema (rule 7).

### `UserOutletAssignmentChanged`

Emitted by org-service inside the `UserOutletAssignmentWriter.replaceAssignments` transaction — the
**replace-set** that atomically reconciles a user's outlet assignment set (PUT semantics). One event is
emitted per row that CHANGES STATE: a row created or reopened emits `change_kind=ASSIGNED`; a row
closed emits `change_kind=UNASSIGNED`. All events for a single replace-set call commit in ONE
transaction with the DB writes (rule 3). Consumed by restaurant-service (Phase 5 — server-side
outlet-scoping enforcement) to maintain a local `user_outlet_assignment_ref` read model. The consumer
upserts idempotently: `ASSIGNED` sets `active=true`, `UNASSIGNED` sets `active=false`, on conflict
`(company_id, user_id, org_unit_id)`. Carries post-change state. Ids only — no PII.

- **Producer:** `org-service` (via transactional outbox, rule 3, in the same transaction as the
  `user_outlet_assignment` write — rule 3)
- **Consumers:** `restaurant-service` (local `user_outlet_assignment_ref` read model, Phase 5
  outlet-scoping; the OrderWriter gate reads this table for cashier enforcement)
- **Aggregate type / partition key:** `user_outlet_assignment` / `assignment_id` (the outbox
  `aggregate_id`). An `assignment_id` is stable per (user, outlet) tuple, so all events for one
  assignment land on the same partition in order — the guarantee the consumer's last-writer-wins
  upsert needs. Per-USER ordering across different outlets is NOT guaranteed.
- **Outbox `event_type`:** `UserOutletAssignmentChanged`
- **Topic:** `UserOutletAssignmentChanged`
- **Schema (single source of truth):** `libs/contracts/src/main/resources/avro/UserOutletAssignmentChanged.avsc`
  (ADR 0003 — shared schema home; both producer and consumer resolve against the classpath artifact)
- **Full name:** `id.co.nativeapp.events.org.UserOutletAssignmentChanged`

Effective dates are Avro `date` logical-type ints (epoch day), matching the `AssignmentChanged` and
`GroupMembershipChanged` precedents. An open-ended assignment uses the far-future `9999-12-31`
sentinel in `effective_to`, never null (ENGINEERING-STANDARDS §2.5). For an `UNASSIGNED` event
`effective_to` carries the closing date (today); for an `ASSIGNED` event it is `9999-12-31`.

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `assignment_id` | `string` | The org-service `user_outlet_assignment` row id (UUID as string); the restaurant consumer uses this as the PK on insert. Also the outbox `aggregate_id` and therefore the Kafka partition key |
| `user_id` | `string` | The Keycloak subject id (`sub` claim) of the assigned user |
| `company_id` | `string` | The owning tenant / company id (UUID as string); the consumer binds `TenantContext` to this value before the upsert so RLS applies |
| `org_unit_id` | `string` | The outlet org-unit id (UUID as string) this assignment targets |
| `change_kind` | `string` | `ASSIGNED` (row created or reopened — `active=true`) \| `UNASSIGNED` (row closed — `active=false`). A string (not an Avro enum) so adding a kind is backward-compatible |
| `effective_from` | `int` (`date`) | The date the assignment became effective (epoch day) |
| `effective_to` | `int` (`date`) | The date the assignment ceases to be effective (epoch day); `9999-12-31` sentinel when open-ended |

**Avro schema** (single source of truth at `libs/contracts/src/main/resources/avro/UserOutletAssignmentChanged.avsc`)

```json
{
  "type": "record",
  "name": "UserOutletAssignmentChanged",
  "namespace": "id.co.nativeapp.events.org",
  "doc": "Emitted by org-service inside the replace-set transaction when a user's outlet assignment changes: one event per row CREATED/REOPENED (change_kind=ASSIGNED) and one per row CLOSED (change_kind=UNASSIGNED). Consumed by restaurant-service (and the other verticals) to maintain a local user_outlet_assignment_ref read model used for server-side outlet-scoping enforcement. Carries POST-CHANGE state; consumers upsert idempotently on conflict (company_id, user_id, org_unit_id). Ids only — no PII. Partition key is assignment_id (the outbox aggregate_id), which is stable per (user, outlet) tuple: events for one assignment land on the same partition in order. Per-user ordering ACROSS outlets is NOT guaranteed.",
  "fields": [
    {"name": "assignment_id", "type": "string", "doc": "The org-service user_outlet_assignment row id (UUID as string); the PK the consumer uses when inserting. Also the outbox aggregate_id and therefore the Kafka partition key — stable per (user, outlet) tuple."},
    {"name": "user_id", "type": "string", "doc": "The Keycloak subject id (sub claim) of the assigned user. Stable, non-PII identifier."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string). The consumer binds TenantContext to this value before the upsert so RLS applies."},
    {"name": "org_unit_id", "type": "string", "doc": "The outlet org-unit id (UUID as string) this assignment targets."},
    {"name": "change_kind", "type": "string", "doc": "What changed: ASSIGNED (the row was created or reopened — active=true) | UNASSIGNED (the row was closed — active=false). A string (not an Avro enum) so adding a kind is backward-compatible."},
    {"name": "effective_from", "type": {"type": "int", "logicalType": "date"}, "doc": "The date the assignment became (or becomes) effective (epoch day). For an UNASSIGNED event this is the original open date; for ASSIGNED it is today."},
    {"name": "effective_to", "type": {"type": "int", "logicalType": "date"}, "doc": "The date the assignment ceases to be effective (epoch day). 9999-12-31 sentinel when open-ended (ASSIGNED); today's date when closed (UNASSIGNED)."}
  ]
}
```

**Idempotency (consumer).** The restaurant consumer dedupes by the event UUID from the Kafka `id`
header (`ProcessedEventStore.processOnce`). A re-delivered event is a clean no-op. The upsert
targets `ON CONFLICT ON CONSTRAINT uq_user_outlet_assignment_ref_scope (company_id, user_id,
org_unit_id) DO UPDATE` so the same ASSIGNED event delivered twice merely sets `active=true` again —
no row is duplicated. A missing or non-UUID `id` header is a producer-side contract violation: the
consumer fails closed (routes to `UserOutletAssignmentChanged.DLT`).

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default (e.g. an
optional `outlet_name` as `["null","string"]` with `default: null`). Never add a required field
without a default, never remove or rename a field, never change a field's type. The contract test
(`UserOutletAssignmentChangedContractTest`, restaurant-service) enforces the triad — parse + `AvroSerde`
round-trip + add-optional compatible / new-required broken.

### `UserOutletAssignmentChanged` — restaurant-service consumer view

restaurant-service **consumes** the org-service `UserOutletAssignmentChanged` (the producer contract
is documented above) into a **local `user_outlet_assignment_ref` read model** — the table the
`OutletAccessGuard` reads (from both the `OrderWriter` and `BillWriter` paths) to enforce that a
cashier may only ring sales at an outlet they are assigned to (Phase 5 enforcement policy, signed
off). The consumer reads the schema from
`libs/contracts` on the classpath (`avro/UserOutletAssignmentChanged.avsc`, the single source of truth
— ADR 0003), reads the outbox payload as **raw Avro bytes** via `libs/events AvroSerde` (no Schema
Registry serde), and dedupes by the event UUID (`ProcessedEventStore`) so a re-delivery never
double-applies. The upsert runs inside a `TenantContext` scope bound to the event's `company_id` (RLS
applies — rule 5). The `UserOutletAssignmentChangedContractTest` asserts the schema stays
backward-compatible with the producer schema (rule 7).

**Enforcement policy (signed off, Phase 5).** Applied by the shared `OutletAccessGuard`
(`outletref.service`) in EVERY sale-recording write path — the order paths (`checkout`, `park`,
`payParked`) AND the open-bill paths (`open`, `payBill`), so bill tabs cannot sidestep the guard:

- **Owner / manager** — bypass the check entirely (no assignment rows required).
- **Cashier** — must have an `ACTIVE` row in `user_outlet_assignment_ref` for
  `(user_id, org_unit_id = businessId)`. A cashier with ZERO rows for the company is rejected (`403
  outlet-not-assigned`) — **default-closed**.
- **Grandfather clause** — if the company has **ZERO rows** in `user_outlet_assignment_ref`
  (across all users, RLS-scoped to the tenant), outlet scoping has never been adopted by that
  tenant and the cashier is **ALLOWED** (preserves pre-outlet behavior for tenants that never
  assigned anyone — such tenants only ever ring their implicit first business anyway, since the
  console binds the POS to `firstBusinessId` until an outlet is picked). The moment the first
  assignment event lands for the company, the clause stops applying and every cashier is
  default-closed. Adoption is detected purely from local `user_outlet_assignment_ref` presence —
  restaurant-service has no org-unit read model, so it cannot (and does not need to) resolve
  "the first business" itself.

**Topic consumed:** `UserOutletAssignmentChanged`.

### `UserOutletAssignmentChanged` — carwash-service consumer view

carwash-service **ports** the identical consumer shape restaurant-service uses (verbatim, package
renamed to `id.co.nativeapp.carwash.outletref`): the same `user_outlet_assignment_ref` table shape
(V7), the same `OutletAccessGuard` policy (owner/manager bypass → active-assignment check →
zero-rows grandfather clause → `OutletNotAssignedException` 403), and the same schema-resolution
approach — reads `avro/UserOutletAssignmentChanged.avsc` from the shared `libs/contracts` classpath
resource (no local per-service copy, matching restaurant-service, which does not keep one for this
event either), decodes raw Avro bytes via `libs/events AvroSerde`, and dedupes by event UUID
(`ProcessedEventStore`).

**One deliberate shape deviation from restaurant-service:** the carwash listener throws
carwash-service's already-SHARED `config.EventDecodeException` / `config.MissingEventIdException`
(the same types `EntitlementEventListener` and `StaffEventListener` already throw) instead of
restaurant's feature-local `UserOutletAssignmentDecodeException` /
`UserOutletAssignmentMissingEventIdException` pair — carwash's single `KafkaConfig.kafkaErrorHandler`
already registers the shared pair as non-retryable, so no wiring change was needed to add this
consumer.

**Scope of this port.** This lands the guard + read model + consumer ONLY, as the foundation for
carwash's POS-parity work — no ticket/checkout write path calls `OutletAccessGuard.enforce` yet (that
lands with the ticket-checkout feature, a separate task); until then the guard and its read model are
exercised only by tests (`OutletAccessGuardTest`, a test-only `@Transactional` invoker standing in for
the future checkout writer).

**Topic consumed:** `UserOutletAssignmentChanged`.

---

### `GroupDefined`

Emitted by org-service when a **consolidation group** is defined (P3d SEAM 1 — the group model +
membership read model). A consolidation group is a named set of companies whose books a **lead
company** consolidates; it is owned by that lead. Consumed by finance-service to register the group
in a **local group read model** (`group_id -> {lead_company_id, reporting_currency}`) so finance
knows the group's lead + reporting currency without a synchronous call to org-service (rule 2). This
seam is **pure plumbing — NO consolidation math**; later seams read this reference to drive group
consolidation.

- **Producer:** `org-service` (the consolidation-group create flow).
- **Consumers:** `finance-service` (the local `group_ref` read model) — **LIVE (P3d SEAM 1)**.
- **Aggregate type / partition key:** `consolidation_group` / `group_id`
- **Outbox `event_type`:** `GroupDefined`
- **Schema (producer, source of truth):** `services/org-service/src/main/resources/avro/GroupDefined.avsc`
- **Schema (finance consumer copy):** `services/finance-service/src/main/resources/avro/GroupDefined.avsc`
  — finance owns its own consumer view of the contract; the finance `GroupDefinedContractTest` asserts
  the copy stays backward-compatible with the producer schema (rule 7). The finance consumer reads the
  outbox payload as **raw Avro bytes** via `libs/events AvroSerde` (no Schema Registry serde), deduping
  by the event UUID (`ProcessedEventStore`) so a re-delivery never double-applies.
- **Full name:** `id.co.nativeapp.events.org.GroupDefined`

**`reporting_currency` is an ISO-4217 code, immutable once set** (CLAUDE.md "Settings live at
creation") — the exact pattern `CompanyCreated.base_currency` uses. It is a currency *code*, never a
monetary amount and never a float. `lead_company_id` is the group's owner/tenant; the group and its
membership are RLS-scoped to it (a member company can neither enumerate nor mutate the group).

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `group_id` | `string` | The consolidation group aggregate id (UUID as string); the partition key |
| `lead_company_id` | `string` | The lead/owner company that administers the group (UUID as string) |
| `reporting_currency` | `string` | The group's reporting (presentation) currency: an ISO-4217 code (e.g. `IDR`, `USD`); immutable |
| `name` | `string` | The group display name |

**Avro schema**

```json
{
  "type": "record",
  "name": "GroupDefined",
  "namespace": "id.co.nativeapp.events.org",
  "doc": "Emitted by org-service when a consolidation group is defined (P3d SEAM 1); consumed by finance-service to register the group's lead company + immutable reporting currency in a local group read model, without a synchronous call (rule 2). The group is owned by its lead company; reporting_currency is set once at creation and is immutable (CLAUDE.md 'Settings live at creation').",
  "fields": [
    {"name": "group_id", "type": "string", "doc": "The consolidation_group aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "lead_company_id", "type": "string", "doc": "The lead/owner company that administers the group (UUID as string); the group's tenant."},
    {"name": "reporting_currency", "type": "string", "doc": "The group's reporting (presentation) currency: an ISO-4217 code (e.g. IDR, USD). Immutable once set."},
    {"name": "name", "type": "string", "doc": "The group display name."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default. Never
add a required field without a default, never remove or rename a field, never change a field's type.
The contract tests (`GroupEventContractTest` producer-side in org-service, `GroupDefinedContractTest`
consumer-side in finance) enforce the triad — parse + `AvroSerde` round-trip + back-compat
for-change / against-break.

### `GroupMembershipChanged`

Emitted by org-service when a company is **added to** or **removed from** a consolidation group (P3d
SEAM 1). A removal **closes the membership's effective window** (`effective_to` stamped to the
removal date) — it never hard-deletes, so membership history is preserved for re-runnable
consolidation. Consumed by finance-service to maintain the **active member set** of its local group
read model (`group_member`), again without a synchronous call (rule 2).

- **Producer:** `org-service` (the add-member / remove-member flow).
- **Consumers:** `finance-service` (the local `group_member` read model) — **LIVE (P3d SEAM 1)**.
- **Aggregate type / partition key:** `consolidation_group` / `group_id` (so a group's membership
  events are ordered, and finance's `GroupDefined` for the group precedes them).
- **Outbox `event_type`:** `GroupMembershipChanged`
- **Schema (producer, source of truth):** `services/org-service/src/main/resources/avro/GroupMembershipChanged.avsc`
- **Schema (finance consumer copy):** `services/finance-service/src/main/resources/avro/GroupMembershipChanged.avsc`
  — finance owns its own consumer view; `GroupMembershipChangedContractTest` asserts back-compat with
  the producer schema (rule 7). Raw Avro bytes via `libs/events AvroSerde`, deduped by event UUID
  (`ProcessedEventStore`).
- **Full name:** `id.co.nativeapp.events.org.GroupMembershipChanged`

**Effective dates are Avro `date` logical-type ints** (epoch day), exactly as `AssignmentChanged`
carries its effective dates; an open membership uses the far-future `9999-12-31` sentinel, never null.
The event carries the **post-change** state so a consumer applies it idempotently. It carries no
`lead_company_id`; finance resolves the owning lead (the tenant the `group_member` write is bound to)
from its local group reference, keyed on `group_id` — a cached read model, never a sync call (rule 2).

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `group_id` | `string` | The consolidation group id (UUID as string); the partition key |
| `member_company_id` | `string` | The member company added or removed (UUID as string) |
| `change_kind` | `string` | The transition: `ADDED` \| `REMOVED` |
| `effective_from` | `int` (`date`) | The date the membership becomes effective (epoch day) |
| `effective_to` | `int` (`date`) | The date it ceases to be effective; `9999-12-31` while open |

**Avro schema**

```json
{
  "type": "record",
  "name": "GroupMembershipChanged",
  "namespace": "id.co.nativeapp.events.org",
  "doc": "Emitted by org-service when a company is added to or removed from a consolidation group (P3d SEAM 1); consumed by finance-service to maintain the active member set of a local group read model, without a synchronous call (rule 2). A removal CLOSES the effective_to window (no hard-delete); effective dates are date logical-type ints (epoch day), open-ended = 9999-12-31.",
  "fields": [
    {"name": "group_id", "type": "string", "doc": "The consolidation_group id (UUID as string); also the Kafka partition key."},
    {"name": "member_company_id", "type": "string", "doc": "The member company added or removed (UUID as string)."},
    {"name": "change_kind", "type": "string", "doc": "The membership transition: ADDED | REMOVED."},
    {"name": "effective_from", "type": {"type": "int", "logicalType": "date"}, "doc": "The date the membership becomes effective (epoch day)."},
    {"name": "effective_to", "type": {"type": "int", "logicalType": "date"}, "doc": "The date the membership ceases to be effective (epoch day); the 9999-12-31 sentinel while the membership is open."}
  ]
}
```

**Compatibility.** Backward-compatible only, enforced by `GroupEventContractTest` (org-service) and
`GroupMembershipChangedContractTest` (finance) — the triad (parse + `AvroSerde` round-trip +
add-optional compatible / new-required broken).

### `GroupDefined` / `GroupMembershipChanged` — finance-service consumer view

finance-service **consumes** the org-service `GroupDefined` / `GroupMembershipChanged` into a **local
group read model** (rule 2 — never a sync call): `group_ref` (`group_id -> {lead_company_id,
reporting_currency}`) and `group_member` (`group_id -> the effective-dated active member set`). It
keeps its own **consumer copies** of the schemas at
`services/finance-service/src/main/resources/avro/GroupDefined.avsc` and
`.../GroupMembershipChanged.avsc` (same full names), reads the outbox payload as **raw Avro bytes** via
`libs/events AvroSerde`, and dedupes by the event UUID (`ProcessedEventStore`) so a re-delivery never
double-applies. `group_ref` and `group_member` are **Auditable + under FORCE RLS scoped to the LEAD
company** (`company_id = lead_company_id`) — the existing finance org-derived-read-model precedent
(consolidated_revenue / consolidated_pnl tenancy); **SEAM 1 does NOT introduce the group-scoped GUC
(that is SEAM 2)**. `GroupDefined` carries the lead in its payload; `GroupMembershipChanged` does not,
so finance resolves the lead from a small cross-tenant `group_lead` reference mapping (`group_id ->
lead_company_id`, NOT under RLS — like `chart_of_account` / `processed_event`), written alongside
`group_ref`, then binds that lead before the RLS-scoped `group_member` write. A membership event for a
group whose `GroupDefined` has not yet been consumed throws a **retryable** `UnknownGroupException`
(re-delivered until the group is registered), never projected under an unknown tenant. A record without
a valid `id` header fails closed to `<topic>.DLT`; an undecodable payload routes to the DLT
(non-retryable). The `GroupDefinedContractTest` / `GroupMembershipChangedContractTest` assert the
consumer copies stay backward-compatible with the producer schemas (rule 7).

### `TrialBalancePublished`

Emitted by a **member company's** finance **within-company close** when its trial balance for a period
is finalized. Consumed by the **group consolidation** in finance-service to ingest the member's
trial-balance lines into the group's `group_trial_balance` read model, so the group close can run
consolidation + intercompany elimination. **Both sides are now LIVE: the CONSUMER (the ingest) shipped
in P3d SEAM 2; the PRODUCER (the within-company close that emits it) shipped in P3d SEAM 4a.** SEAM 2
introduced the **two-GUC group RLS core**: a group table is scoped by the CONJUNCTION `group_id =
current_setting('app.current_group') AND company_id = current_setting('app.current_tenant')`, where
`company_id` is the group's LEAD company. A group read/write therefore requires BOTH the group scope
AND the lead-company tenant; any partial/unbound state fails closed, and a normal single-tenant request
(which never sets `app.current_group`) sees no group rows at all (the single-tenant path is
byte-identical).

**PRODUCER (P3d SEAM 4a).** A within-company close (`WithinCompanyCloseWriter`) gathers the company's
BALANCED trial balance from the dimensional ledger — its REVENUE + EXPENSE lines by `gl_account`
(`account_type` resolved from `chart_of_account`) PLUS a BALANCING retained-earnings EQUITY closing
line equal to `−(Σ REVENUE − Σ EXPENSE)` so the lines sum SIGNED-TO-ZERO in the company's functional
currency (the P&L closes to equity — exactly what makes the member trial balance pass the SEAM-3b
native-balance gate). It emits one `TrialBalancePublished` **per group the company is active in at
period-end** (resolved from a `member_group_index` reverse-index reference table — rule 2, never a sync
call), with `reconciled = true` (the closing equity line guarantees it) and `uses_illustrative_rules`
sticky-OR-ed from the period's postings. A company in no group does the within-company close but emits
no `TrialBalancePublished` (it still emits `ConsolidationClosed(group_id = null)`). All emissions are
via the transactional outbox (rule 3), atomic with the close.

- **Producer:** `finance-service` (the within-company close path) — **LIVE (P3d SEAM 4a)**.
- **Consumers:** `finance-service` (the group `group_trial_balance` ingest) — **LIVE (P3d SEAM 2)**.
- **Aggregate type / partition key:** `consolidation_group` / `group_id` (so a group's member trial
  balances are ordered after its `GroupDefined`, the way the group read model is hydrated first).
- **Outbox `event_type`:** `TrialBalancePublished`
- **Schema (producer source of truth + finance consumer copy — ONE `.avsc`):** `services/finance-service/src/main/resources/avro/TrialBalancePublished.avsc`
  — both the producer (`TrialBalancePublishedSchema.toRecord`) and the consumer
  (`TrialBalancePublishedSchema.decode`) read this single schema, so a produced event is
  decode-compatible with the consumer by construction; the finance `TrialBalancePublishedContractTest`
  asserts back-compat (rule 7). The consumer reads the outbox payload as **raw Avro bytes** via
  `libs/events AvroSerde` (no Schema Registry serde), deduping by the **real, globally-unique event
  UUID** (`ProcessedEventStore`) plus a `(source_event_id, gl_account_code, posting_type)` UNIQUE
  backstop, so a re-delivery never double-ingests.
- **Full name:** `id.co.nativeapp.events.finance.TrialBalancePublished`

**`company_id` is the MEMBER company** whose trial balance this is — a *dimension* on the ingested
lines (stored as `member_company_id`), NOT the tenant. The ingested `group_trial_balance` rows are
owned by the group's **LEAD** company (`company_id = lead`, resolved from finance's local `group_lead`
reference mapping — rule 2, never a sync call), and the write is bound to `(tenant = lead, group =
group_id)` so the conjunction `WITH CHECK` passes. A member can therefore neither enumerate nor mutate
the group's accumulated trial balance. An event for a group whose `GroupDefined` has not yet been
consumed throws a **retryable** `UnknownGroupException` (re-delivered until the group is registered),
never ingested under an unknown tenant. A record without a valid `id` header fails closed to
`TrialBalancePublished.DLT`; an undecodable payload (or a line with an unknown `account_type`) routes
to the DLT (non-retryable). Money is integer minor units + an ISO-4217 currency, **never a float**
(rule 8).

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `company_id` | `string` | The MEMBER company whose trial balance this is (UUID as string); a dimension, not the tenant |
| `group_id` | `string` | The consolidation group the member belongs to (UUID as string); the partition key + the second RLS dimension |
| `period` | `string` | The accounting period these lines reconcile for (`YYYY-MM`) |
| `base_currency` | `string` | The member's base (functional) currency: an ISO-4217 code |
| `reconciled` | `boolean` | Whether the member's trial balance reconciled (debits == credits) before publish |
| `uses_illustrative_rules` | `boolean` | Whether the figures are illustrative-placeholder-derived (self-describing for an auditor) |
| `lines` | `array<TrialBalanceLine>` | The member's trial-balance lines for the period |
| `lines[].gl_account_code` | `string` | The resolved chart_of_account account code (the dimension) |
| `lines[].account_type` | `string` | The GL account class: `ASSET` \| `LIABILITY` \| `EQUITY` \| `REVENUE` \| `EXPENSE` |
| `lines[].posting_type` | `string` | The posting kind, carried verbatim from the member's books |
| `lines[].amount_minor` | `long` | The line amount in the currency's minor units (integer; never a float) |
| `lines[].currency` | `string` | The line's ISO-4217 currency code |
| `lines[].related_party_counterparty_id` | `["null","string"]` (default `null`) | The intercompany counterparty (UUID as string) for a related-party line, else null (SEAM-3 elimination) |
| `lines[].intercompany_ref` | `["null","string"]` (default `null`) | A free-form intercompany reference for a related-party line, else null |

**Avro schema**

```json
{
  "type": "record",
  "name": "TrialBalancePublished",
  "namespace": "id.co.nativeapp.events.finance",
  "doc": "Emitted by a member company's finance close when its trial balance for a period is finalized (P3d SEAM 2); consumed by the group consolidation in finance-service to ingest the member's trial-balance lines into group_trial_balance, bound to the group's LEAD tenant + group scope (the two-GUC conjunction). SEAM 2 ships only the consumer; the producer is SEAM 3/4. company_id is the MEMBER company (a dimension), not the tenant. Money is integer minor units + ISO-4217 currency, never a float.",
  "fields": [
    {"name": "company_id", "type": "string", "doc": "The MEMBER company whose trial balance this is (UUID as string). A dimension on the ingested lines, NOT the group tenant."},
    {"name": "group_id", "type": "string", "doc": "The consolidation group the member belongs to (UUID as string); the Kafka partition key and the second RLS dimension."},
    {"name": "period", "type": "string", "doc": "The accounting period these lines reconcile for (YYYY-MM)."},
    {"name": "base_currency", "type": "string", "doc": "The member's base (functional) currency: an ISO-4217 code (e.g. IDR, USD)."},
    {"name": "reconciled", "type": "boolean", "doc": "Whether the member's trial balance reconciled (debits == credits) before publish."},
    {"name": "uses_illustrative_rules", "type": "boolean", "doc": "Whether the figures are derived from ILLUSTRATIVE_PLACEHOLDER statutory rules (self-describing for an auditor)."},
    {
      "name": "lines",
      "doc": "The member's trial-balance lines for the period.",
      "type": {
        "type": "array",
        "items": {
          "type": "record",
          "name": "TrialBalanceLine",
          "fields": [
            {"name": "gl_account_code", "type": "string", "doc": "The resolved chart_of_account account code (the dimension)."},
            {"name": "account_type", "type": "string", "doc": "The GL account class: ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE."},
            {"name": "posting_type", "type": "string", "doc": "The posting kind carried verbatim from the member's books."},
            {"name": "amount_minor", "type": "long", "doc": "The line amount in the currency's minor units (integer; never a float)."},
            {"name": "currency", "type": "string", "doc": "The line's ISO-4217 currency code (e.g. IDR, USD)."},
            {"name": "related_party_counterparty_id", "type": ["null", "string"], "default": null, "doc": "The intercompany counterparty (UUID as string) for a related-party line, else null. SEAM-3 elimination."},
            {"name": "intercompany_ref", "type": ["null", "string"], "default": null, "doc": "A free-form intercompany reference for a related-party line, else null."}
          ]
        }
      }
    }
  ]
}
```

**Compatibility.** Backward-compatible only: add fields with a default, never add a required field
without a default, never remove/rename a field or change a type. The finance
`TrialBalancePublishedContractTest` enforces the triad (parse + `AvroSerde` round-trip +
add-optional-compatible / new-required-broken).

---

### `SaleVoided`

Emitted by restaurant-service when a captured payment is fully voided before settlement
(ADR 0006, slice 4). Finance consumes this to reverse the original `SaleRecorded` ledger
posting with a contra entry (credit clearing, debit revenue — the inverse of the SALE
template). An idempotent consumer uses the `void_id` as the dedup key via
`ProcessedEventStore`.

- **Producer:** `restaurant-service`
- **Consumers:** `finance-service` (reverse the ledger posting + contra journal entry)
- **Aggregate type / partition key:** `sale` / `sale_id`
- **Outbox `event_type`:** `SaleVoided`
- **Schema:** `libs/contracts/src/main/resources/avro/SaleVoided.avsc`
- **Full name:** `id.co.nativeapp.events.restaurant.SaleVoided`

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `void_id` | `string` | Unique id for this void event (UUID as string); the reversal idempotency key |
| `sale_id` | `string` | The sale being voided (UUID as string); matches the original `SaleRecorded` |
| `payment_id` | `string` | The payment aggregate being voided (UUID as string) |
| `company_id` | `string` | The owning tenant (UUID as string) |
| `business_id` | `string` | The originating business unit (UUID as string) |
| `amount_minor` | `long` | The voided amount in minor units; never a float |
| `currency` | `string` | ISO-4217 currency code |
| `occurred_at` | `timestamp-millis` | When the void occurred, epoch millis UTC |
| `tender_type` | `["null","string"]` (default `null`) | Original tender (CASH/QRIS/CARD or null) — finance uses this to reverse the correct clearing account |

**Avro schema** (single source of truth in `libs/contracts/src/main/resources/avro/SaleVoided.avsc`)

```json
{
  "type": "record",
  "name": "SaleVoided",
  "namespace": "id.co.nativeapp.events.restaurant",
  "doc": "Emitted by restaurant-service when a captured payment is fully voided before settlement. Finance consumes this to reverse the original SaleRecorded ledger posting with a contra entry. Money is an integer minor-units amount plus an ISO-4217 currency code, never a float (CLAUDE.md rule 8).",
  "fields": [
    {"name": "void_id", "type": "string", "doc": "The void event's unique id (UUID as string); the idempotency key for the reversal."},
    {"name": "sale_id", "type": "string", "doc": "The sale being voided (UUID as string); matches the SaleRecorded sale_id."},
    {"name": "payment_id", "type": "string", "doc": "The payment aggregate being voided (UUID as string)."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "business_id", "type": "string", "doc": "The originating business unit (UUID as string)."},
    {"name": "amount_minor", "type": "long", "doc": "The voided amount in the currency's minor units. Never a float."},
    {"name": "currency", "type": "string", "doc": "ISO-4217 currency code, e.g. IDR or USD."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the void occurred, epoch millis (UTC)."},
    {"name": "tender_type", "type": ["null", "string"], "default": null, "doc": "The original payment tender type (CASH | QRIS | CARD, or null for legacy). Finance uses this to reverse the correct clearing account."}
  ]
}
```

---

### `SaleRefunded`

Emitted by restaurant-service when a captured payment is partially or fully refunded after
settlement (ADR 0006, slice 4). Finance consumes this to post a proportional contra entry
reversing the original `SaleRecorded` ledger posting by the refunded amount. An idempotent
consumer uses the `refund_id` as the dedup key via `ProcessedEventStore`.

- **Producer:** `restaurant-service`
- **Consumers:** `finance-service` (contra ledger posting for the refunded amount)
- **Aggregate type / partition key:** `sale` / `sale_id`
- **Outbox `event_type`:** `SaleRefunded`
- **Schema:** `libs/contracts/src/main/resources/avro/SaleRefunded.avsc`
- **Full name:** `id.co.nativeapp.events.restaurant.SaleRefunded`

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `refund_id` | `string` | Unique id for this refund event (UUID as string); the reversal idempotency key |
| `sale_id` | `string` | The sale being refunded (UUID as string); matches the original `SaleRecorded` |
| `payment_id` | `string` | The payment aggregate being refunded (UUID as string) |
| `company_id` | `string` | The owning tenant (UUID as string) |
| `business_id` | `string` | The originating business unit (UUID as string) |
| `refund_amount_minor` | `long` | The refunded amount in minor units (partial or full); never a float |
| `currency` | `string` | ISO-4217 currency code |
| `total_refunded_minor` | `long` | Cumulative total refunded (including this refund) in minor units; never a float |
| `occurred_at` | `timestamp-millis` | When the refund occurred, epoch millis UTC |
| `tender_type` | `["null","string"]` (default `null`) | Original tender (CASH/QRIS/CARD or null) — finance uses this to reverse the correct clearing account |

**Avro schema** (single source of truth in `libs/contracts/src/main/resources/avro/SaleRefunded.avsc`)

```json
{
  "type": "record",
  "name": "SaleRefunded",
  "namespace": "id.co.nativeapp.events.restaurant",
  "doc": "Emitted by restaurant-service when a captured payment is partially or fully refunded after settlement. Finance consumes this to post a proportional contra entry reversing the original SaleRecorded ledger posting by the refunded amount. Money is an integer minor-units amount plus an ISO-4217 currency code, never a float (CLAUDE.md rule 8).",
  "fields": [
    {"name": "refund_id", "type": "string", "doc": "The refund event's unique id (UUID as string); the idempotency key for the reversal posting."},
    {"name": "sale_id", "type": "string", "doc": "The sale being refunded (UUID as string); matches the SaleRecorded sale_id."},
    {"name": "payment_id", "type": "string", "doc": "The payment aggregate being refunded (UUID as string)."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "business_id", "type": "string", "doc": "The originating business unit (UUID as string)."},
    {"name": "refund_amount_minor", "type": "long", "doc": "The refunded amount in the currency's minor units (partial or full). Never a float."},
    {"name": "currency", "type": "string", "doc": "ISO-4217 currency code, e.g. IDR or USD."},
    {"name": "total_refunded_minor", "type": "long", "doc": "Cumulative total refunded (including this refund) in minor units. Never a float."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the refund occurred, epoch millis (UTC)."},
    {"name": "tender_type", "type": ["null", "string"], "default": null, "doc": "The original payment tender type (CASH | QRIS | CARD, or null for legacy). Finance uses this to reverse the correct clearing account."}
  ]
}
```

---

## Phase 4 (ADR 0027) — loyalty + gift cards: schema foundation

The four events below are the **event-contract foundation** of Phase 4 of the POS-parity program
(ADR 0027 — loyalty points + gift cards). A NEW `loyalty-service` will own members (PII — phone,
name — encrypted THERE, never on an event) and append-only points / gift-card ledgers. The
verticals emit facts (`SaleRecorded`'s Phase 4 fields above, and `GiftCardSold` below);
loyalty-service consumes those and publishes **absolute-value** balance events
(`LoyaltyBalanceChanged`, `GiftCardStateChanged`) the verticals cache as local read models
(`member_balance_ref` / `gift_card_ref`, set-if-newer on `balance_seq`) so a checkout can validate a
redemption without a synchronous call to loyalty-service (rule 2). Gift-card redemption is a
**tender settlement** (`SaleRecorded.gift_card_redeemed_minor`); points redemption is a
**contra-revenue discount** (`SaleRecorded.loyalty_redeemed_minor`); an **earn is memo-only** — no
GL posting at earn time. `LoyaltyRedemptionFlagged` is the eventual-consistency overdraft signal
when a vertical's local cache let a redemption through that the authoritative ledger then found
insufficient.

**Status of this wave.** All four schemas are registered in `libs/contracts` (the single source of
truth, ADR 0003) and covered by schema-parse + round-trip tests (see each section's Compatibility
note for where those tests live — `loyalty-service` does not exist yet, so its own contract-test
suite now EXISTS in loyalty-service). **Producer/consumer machinery is now BUILT (later Phase-4 waves)** —
this is deliberately schema-and-catalog-only; the finance posting logic, the verticals' gift-card
write paths, and `loyalty-service` itself are later waves.

### `GiftCardSold`

Emitted by a vertical when a gift card is sold or topped up at checkout. **NOT a sale — a
LIABILITY event:** the company now owes the bearer stored value. **Never merged into
`SaleRecorded`** — a gift-card sale is not revenue at the point of sale; revenue is recognised
later, at redemption, via `SaleRecorded.gift_card_redeemed_minor`.

- **Producer:** the verticals (`restaurant-service`, `carwash-service`, `barbershop-service`), once
  each ships a gift-card sale/top-up write path (a later wave — not built in this schema-only wave).
- **Consumers:** `finance-service` (post the `GIFT_CARD_LIABILITY` credit — a later wave) and
  `loyalty-service` (append to the card's balance ledger and publish `GiftCardStateChanged` — a
  later wave, once loyalty-service is scaffolded).
- **Aggregate type / partition key:** `gift_card` / `gift_card_id` — **not** `gift_card_sale_id` —
  so every event for one card (a sale, a later redemption via `SaleRecorded`, a state change)
  orders on the same partition, which `loyalty-service`'s balance ledger needs.
- **Outbox `event_type`:** `GiftCardSold`
- **Schema (single source of truth, ADR 0003):** `libs/contracts/src/main/resources/avro/GiftCardSold.avsc`
- **Full name:** `id.co.nativeapp.events.restaurant.GiftCardSold`

**Namespace decision.** `GiftCardSold` has THREE potential vertical producers, like
`SaleRecorded`/`SaleVoided`/`SaleRefunded`/`ExpenseRecorded` before it. This catalog follows the
SAME house-style precedent those events set: a multi-vertical-producer event keeps **one stable
namespace** (`id.co.nativeapp.events.restaurant`, the historic producer-of-record) regardless of
which vertical actually emits it, so every producer shares one full name on the wire and finance
reads them all through the identical consumer path. (Contrast `MetricPublished`/`PeriodSealed`,
which use the `carwash` namespace because carwash was the FIRST vertical to declare those
contracts — the convention is "whichever vertical the schema is conceptually anchored to," not a
fixed rule; for `GiftCardSold`, anchoring it to the `SaleRecorded` family made the most sense since
it is a sibling revenue/liability fact about the same POS checkout.)

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `gift_card_sale_id` | `string` | This gift-card-sale event's unique id (UUID as string) — the idempotency/dedupe key (via `ProcessedEventStore`), not the Kafka offset |
| `gift_card_id` | `string` | The gift card aggregate id (UUID as string) being sold/topped up; the partition key |
| `company_id` | `string` | The owning tenant (UUID as string) |
| `business_id` | `string` | The originating business unit / outlet the card was sold at (UUID as string) |
| `amount_minor` | `long` | The stored-value amount sold/loaded onto the card, in the currency's minor units. Never a float |
| `currency` | `string` | ISO-4217 currency code (e.g. `IDR`, `USD`) |
| `tender_type` | `["null","string"]` (default `null`) | The payment tender used to purchase/top up the card: `CASH` \| `QRIS` \| `CARD`, or `null`. Same routing convention as `SaleRecorded.tender_type` |
| `occurred_at` | `long` (`timestamp-millis`) | When the sale/top-up occurred, epoch millis UTC |

**Avro schema**

```json
{
  "type": "record",
  "name": "GiftCardSold",
  "namespace": "id.co.nativeapp.events.restaurant",
  "doc": "Emitted by a vertical (restaurant-service, carwash-service, barbershop-service) when a gift card is sold or topped up at checkout (ADR 0027, Phase 4 of the POS-parity program). NOT a sale -- a LIABILITY event: the company now owes the bearer stored value. Never merged into SaleRecorded (a gift-card sale is not revenue at the point of sale; revenue is recognised later, at redemption, via SaleRecorded.gift_card_redeemed_minor). Consumed by finance-service (post the GIFT_CARD_LIABILITY credit) and loyalty-service (append to the card's balance ledger and publish GiftCardStateChanged). Money is an integer minor-units amount plus an ISO-4217 currency code, never a float (CLAUDE.md rule 8). Namespace follows the SaleRecorded/SaleVoided/SaleRefunded/ExpenseRecorded house-style precedent: a multi-vertical-producer event keeps ONE stable namespace (restaurant, the historic producer-of-record) regardless of which vertical emits it, so all producers share one full name on the wire.",
  "fields": [
    {"name": "gift_card_sale_id", "type": "string", "doc": "This gift-card-sale event's unique id (UUID as string) -- the idempotency/dedupe key consumers use via ProcessedEventStore (not the Kafka offset)."},
    {"name": "gift_card_id", "type": "string", "doc": "The gift card aggregate id (UUID as string) being sold or topped up; also the Kafka partition key so every event for one card (sale, redemption, state change) orders on the same partition."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "business_id", "type": "string", "doc": "The originating business unit / outlet the card was sold at (UUID as string)."},
    {"name": "amount_minor", "type": "long", "doc": "The stored-value amount sold or loaded onto the card, in the currency's minor units. Never a float."},
    {"name": "currency", "type": "string", "doc": "ISO-4217 currency code, e.g. IDR or USD."},
    {"name": "tender_type", "type": ["null", "string"], "default": null, "doc": "The payment tender type used to purchase/top up the card: CASH | QRIS | CARD, or null for legacy/unspecified. Finance routes the offsetting clearing-account debit by tender, the same convention as SaleRecorded.tender_type."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the gift-card sale/top-up occurred, epoch millis (UTC)."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default. Never
add a required field without a default, never remove or rename a field, never change a field's
type. **Tests this wave:** the schema parses (`Schema.Parser().parse`) and a `GenericRecord` built
from it round-trips through `libs/events AvroSerde` — verified ad hoc for this catalog entry (no
`libs/contracts` test module exists — it is resources-only, ADR 0003 — and no producer/consumer
Java class exists yet to host a `*ContractTest`). **Where the real contract tests land:** a
`GiftCardSoldContractTest` in EACH vertical once that vertical builds its `GiftCardSold` producer
(mirroring `SaleRecordedContractTest`'s per-service pattern), and in `loyalty-service` once it is
scaffolded (consumer side + the finance consumer copy's own test). This is a signed-off gap for
this wave (schema-first; the producer/consumer machinery was built in the later Phase-4 waves; schema + catalog + evolution
tests only).

### `LoyaltyBalanceChanged`

Emitted by `loyalty-service` whenever a member's points balance changes: enrollment, an earn, a
redemption, a manual adjustment, or a reversal. Carries the **ABSOLUTE** resulting `points_balance`
(a set-to-value, **not** a delta) plus a monotonic `balance_seq` so a consumer applies only the
newest value and drops a stale/out-of-order redelivery — the log-compacted-topic / snapshot
hydration discipline (ARCHITECTURE.md §3) for the verticals' `member_balance_ref` read model.

- **Producer:** `loyalty-service` (BUILT — Phase 4; outbox-emitted in the same tx as every balance-affecting write).
- **Consumers:** the verticals (`restaurant-service`, `carwash-service`, `barbershop-service`) — a
  local `member_balance_ref` read model, set only when the incoming `balance_seq` is newer than the
  cached one (rule 2 — never a synchronous call to loyalty-service).
- **Aggregate type / partition key:** `loyalty_member` / `member_id`
- **Outbox `event_type`:** `LoyaltyBalanceChanged`
- **Schema (single source of truth, ADR 0003):** `libs/contracts/src/main/resources/avro/LoyaltyBalanceChanged.avsc`
- **Full name:** `id.co.nativeapp.events.loyalty.LoyaltyBalanceChanged`

**No PII (rule 6).** `member_id` is an opaque UUID; the member's name and phone stay column-encrypted
in `loyalty-service` and never cross this event. A consumer that needs more (e.g. displaying a
member's name at checkout) reads its own slice or calls loyalty-service's future snapshot/bootstrap
API — never this event.

**Set-if-newer semantics.** Unlike `MetricPublished` (which ACCUMULATES a delta per event), this
event is a **snapshot replacement**: a consumer applying event N with `balance_seq = s` must reject
any later-processed event with `balance_seq <= s` for the same `member_id` (redelivery or
out-of-order Kafka delivery within a partition should not happen, but a consumer restart replaying
from an earlier offset, or a future multi-partition topic, could reorder across partitions — the
`balance_seq` guard makes the read model correct regardless).

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `member_id` | `string` | The loyalty member aggregate id (UUID as string); the partition key |
| `company_id` | `string` | The owning tenant (UUID as string) |
| `points_balance` | `long` | The member's resulting points balance — an ABSOLUTE set-to-value, not a delta. A ledger unit, not money |
| `balance_seq` | `long` | Monotonically increasing per-member sequence number; a consumer applies an event only if `balance_seq` is strictly greater than its cached value (set-if-newer) |
| `reason` | `string` | Why the balance changed: `ENROLLED` \| `EARNED` \| `REDEEMED` \| `ADJUSTED` \| `REVERSED` |
| `occurred_at` | `long` (`timestamp-millis`) | When the balance change occurred, epoch millis UTC |

**Avro schema**

```json
{
  "type": "record",
  "name": "LoyaltyBalanceChanged",
  "namespace": "id.co.nativeapp.events.loyalty",
  "doc": "Emitted by loyalty-service (ADR 0027, Phase 4 of the POS-parity program) whenever a member's points balance changes: enrollment, an earn, a redemption, a manual adjustment, or a reversal. Carries the ABSOLUTE resulting points_balance (a set-to-value, NOT a delta) plus a monotonic balance_seq so a consumer applies only the newest value and drops a stale or out-of-order redelivery. Consumed by the verticals to maintain a local member_balance_ref read model, set only when the incoming balance_seq is newer than the cached one -- the snapshot/bootstrap hydration path for that read model. NO PII (CLAUDE.md rule 6): member_id is an opaque UUID; the member's name and phone stay column-encrypted in loyalty-service and never cross this event.",
  "fields": [
    {"name": "member_id", "type": "string", "doc": "The loyalty member aggregate id (UUID as string); also the Kafka partition key so one member's balance events order."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "points_balance", "type": "long", "doc": "The member's resulting points balance -- an ABSOLUTE set-to-value, not a delta. A long ledger unit (not money -- points are never a currency amount)."},
    {"name": "balance_seq", "type": "long", "doc": "A monotonically increasing per-member sequence number. A consumer applies an incoming event only when balance_seq is strictly greater than its locally cached value (set-if-newer); a lower or equal balance_seq (redelivery or out-of-order delivery) is dropped, making the consumer idempotent under reordering as well as re-delivery."},
    {"name": "reason", "type": "string", "doc": "Why the balance changed: ENROLLED | EARNED | REDEEMED | ADJUSTED | REVERSED. A string (not an Avro enum), so adding a reason is a backward-compatible change."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the balance change occurred, epoch millis (UTC)."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default. Never
add a required field without a default, never remove or rename a field, never change a field's
type. **Tests this wave:** the schema parses and round-trips through `libs/events AvroSerde` —
verified ad hoc for this catalog entry (no `libs/contracts` test module — resources-only, ADR 0003
— and no producer/consumer Java class exists yet). **Where the real contract tests land:**
`loyalty-service`'s own `LoyaltyBalanceChangedContractTest` (producer side) once it is scaffolded,
and each vertical's consumer-copy contract test once it builds the `member_balance_ref` projection.
Signed-off gap for this wave — schema + catalog + evolution tests only.

### `GiftCardStateChanged`

Emitted by `loyalty-service` whenever a gift card's state or stored-value balance changes:
activation, a redemption, a top-up, expiry, or a void. Carries the **ABSOLUTE** resulting
`balance_minor` (a set-to-value, **not** a delta) plus a monotonic `balance_seq`, the same
set-if-newer discipline as `LoyaltyBalanceChanged`.

- **Producer:** `loyalty-service` (BUILT — Phase 4; outbox-emitted in the same tx as every balance-affecting write).
- **Consumers:** the verticals — a local `gift_card_ref` read model (set only when `balance_seq` is
  newer), read at checkout to validate a redemption WITHOUT a synchronous call to loyalty-service
  (rule 2).
- **Aggregate type / partition key:** `gift_card` / `gift_card_id`
- **Outbox `event_type`:** `GiftCardStateChanged`
- **Schema (single source of truth, ADR 0003):** `libs/contracts/src/main/resources/avro/GiftCardStateChanged.avsc`
- **Full name:** `id.co.nativeapp.events.loyalty.GiftCardStateChanged`

**Money is an integer minor-units amount + an ISO-4217 currency code, never a float** (rule 8).
`balance_minor` is the card's resulting stored-value balance, exactly as `points_balance` is the
member's resulting points balance on `LoyaltyBalanceChanged` — both are absolute set-to-values, not
deltas.

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `gift_card_id` | `string` | The gift card aggregate id (UUID as string); the partition key |
| `company_id` | `string` | The owning tenant (UUID as string) |
| `state` | `string` | The card's resulting state: `ACTIVE` \| `DEPLETED` \| `EXPIRED` \| `VOID` |
| `balance_minor` | `long` | The card's resulting stored-value balance — an ABSOLUTE set-to-value, in minor units. Never a float |
| `currency` | `string` | ISO-4217 currency code (e.g. `IDR`, `USD`) |
| `balance_seq` | `long` | Monotonically increasing per-card sequence number; set-if-newer, same discipline as `LoyaltyBalanceChanged.balance_seq` |
| `occurred_at` | `long` (`timestamp-millis`) | When the state/balance change occurred, epoch millis UTC |

**Avro schema**

```json
{
  "type": "record",
  "name": "GiftCardStateChanged",
  "namespace": "id.co.nativeapp.events.loyalty",
  "doc": "Emitted by loyalty-service (ADR 0027, Phase 4 of the POS-parity program) whenever a gift card's state or stored-value balance changes: activation, a redemption, a top-up, expiry, or a void. Carries the ABSOLUTE resulting balance_minor (a set-to-value, NOT a delta) plus a monotonic balance_seq so a consumer applies only the newest value and drops a stale or out-of-order redelivery. Consumed by the verticals to maintain a local gift_card_ref read model (set only when balance_seq is newer), which a checkout reads to validate a redemption WITHOUT a synchronous call to loyalty-service (rule 2). Money is an integer minor-units amount plus an ISO-4217 currency code, never a float (CLAUDE.md rule 8).",
  "fields": [
    {"name": "gift_card_id", "type": "string", "doc": "The gift card aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "state", "type": "string", "doc": "The card's resulting state: ACTIVE | DEPLETED | EXPIRED | VOID. A string (not an Avro enum), so adding a state is a backward-compatible change."},
    {"name": "balance_minor", "type": "long", "doc": "The card's resulting stored-value balance -- an ABSOLUTE set-to-value, not a delta -- in the currency's minor units. Never a float."},
    {"name": "currency", "type": "string", "doc": "ISO-4217 currency code, e.g. IDR or USD."},
    {"name": "balance_seq", "type": "long", "doc": "A monotonically increasing per-card sequence number. A consumer applies an incoming event only when balance_seq is strictly greater than its locally cached value (set-if-newer); a lower or equal balance_seq (redelivery or out-of-order delivery) is dropped."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the state/balance change occurred, epoch millis (UTC)."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default. Never
add a required field without a default, never remove or rename a field, never change a field's
type. **Tests this wave:** schema-parse + round-trip verified ad hoc for this catalog entry (no
`libs/contracts` test module; no producer/consumer Java class yet). **Where the real contract tests
land:** `loyalty-service`'s own `GiftCardStateChangedContractTest` (producer side) once scaffolded,
and each vertical's consumer-copy test once it builds the `gift_card_ref` projection. Signed-off gap
for this wave.

### `LoyaltyRedemptionFlagged`

Emitted by `loyalty-service` when an **eventual-consistency overdraft** is detected: a vertical
accepted a points or gift-card redemption against its LOCAL cached read model
(`member_balance_ref` / `gift_card_ref`), but loyalty-service's authoritative ledger shows the
account went negative once the redemption was applied there. This is a **manual-follow-up flag, not
a reversal** — the sale already completed and is not undone.

- **Producer:** `loyalty-service` (BUILT — Phase 4; outbox-emitted in the same tx as every balance-affecting write).
- **Consumers:** `notification-service` (alert an operator) — not yet wired; published for when it
  arrives, the same posture `DeliveryReceipt` shipped with.
- **Aggregate type / partition key:** `loyalty_redemption_flag` / `flag_id`
- **Outbox `event_type`:** `LoyaltyRedemptionFlagged`
- **Schema (single source of truth, ADR 0003):** `libs/contracts/src/main/resources/avro/LoyaltyRedemptionFlagged.avsc`
- **Full name:** `id.co.nativeapp.events.loyalty.LoyaltyRedemptionFlagged`

**No PII (rule 6).** `member_id` is an opaque UUID. **Why this can happen at all:** the verticals'
read models (`member_balance_ref` / `gift_card_ref`) are caches, updated asynchronously by
`LoyaltyBalanceChanged` / `GiftCardStateChanged` — under normal operation a checkout's local balance
check is accurate, but a burst of near-simultaneous redemptions across outlets (each reading a
not-yet-updated local cache) can accept more than the authoritative ledger allows. loyalty-service
detects this when it applies the redemption to its own ledger and finds it went negative, and raises
this flag rather than silently allowing (or synchronously blocking, which rule 2 forbids) the
overdraft.

**Key fields**

| Field | Avro type | Meaning |
|---|---|---|
| `flag_id` | `string` | This flag's unique id (UUID as string); the partition key and idempotency/dedupe key |
| `company_id` | `string` | The owning tenant (UUID as string) |
| `business_id` | `string` | The business unit / outlet the redemption was accepted at (UUID as string) |
| `sale_id` | `string` | The `SaleRecorded.sale_id` the overdrawing redemption was applied against (UUID as string) |
| `member_id` | `["null","string"]` (default `null`) | The member whose points redemption overdrew, or `null` for a gift-card-only flag |
| `gift_card_id` | `["null","string"]` (default `null`) | The gift card whose redemption overdrew, or `null` for a points-only flag |
| `shortfall_minor` | `["null","long"]` (default `null`) | The currency-value shortfall (how far negative the gift-card balance went), in minor units, or `null` when not a gift-card shortfall. Never a float |
| `shortfall_points` | `["null","long"]` (default `null`) | The points shortfall, or `null` when not a points shortfall |
| `occurred_at` | `long` (`timestamp-millis`) | When the overdraft was detected, epoch millis UTC |

**Avro schema**

```json
{
  "type": "record",
  "name": "LoyaltyRedemptionFlagged",
  "namespace": "id.co.nativeapp.events.loyalty",
  "doc": "Emitted by loyalty-service (ADR 0027, Phase 4 of the POS-parity program) when an eventual-consistency overdraft is detected: a vertical accepted a points or gift-card redemption against its LOCAL cached read model (member_balance_ref / gift_card_ref), but loyalty-service's authoritative ledger shows the account went negative once the redemption was applied there. This is the eventual-consistency overdraft flag, not a reversal -- the sale already completed and is not undone; it requires a manual follow-up (e.g. contacting the customer, adjusting the member's balance). Consumed by notification-service to alert an operator. NO PII (CLAUDE.md rule 6): member_id is an opaque UUID.",
  "fields": [
    {"name": "flag_id", "type": "string", "doc": "This flag's unique id (UUID as string); also the Kafka partition key and the idempotency/dedupe key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "business_id", "type": "string", "doc": "The business unit / outlet the redemption was accepted at (UUID as string)."},
    {"name": "sale_id", "type": "string", "doc": "The SaleRecorded sale_id the overdrawing redemption was applied against (UUID as string)."},
    {"name": "member_id", "type": ["null", "string"], "default": null, "doc": "The loyalty member whose points redemption overdrew the balance, or null when this flag is for a gift-card overdraft."},
    {"name": "gift_card_id", "type": ["null", "string"], "default": null, "doc": "The gift card whose redemption overdrew the balance, or null when this flag is for a points overdraft."},
    {"name": "shortfall_minor", "type": ["null", "long"], "default": null, "doc": "The currency-value shortfall -- how far negative the gift-card balance went, in minor units -- or null when this flag is not a gift-card shortfall. Never a float."},
    {"name": "shortfall_points", "type": ["null", "long"], "default": null, "doc": "The points shortfall -- how far negative the points balance went -- or null when this flag is not a points shortfall."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the overdraft was detected, epoch millis (UTC)."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a default. Never
add a required field without a default, never remove or rename a field, never change a field's
type. `member_id`/`gift_card_id`/`shortfall_minor`/`shortfall_points` are already nullable unions
(a flag is either a points OR a gift-card overdraft, never both) so a consumer that only cares about
one kind can ignore the other pair. **Tests this wave:** schema-parse + round-trip verified ad hoc
for this catalog entry (no `libs/contracts` test module; no producer/consumer Java class yet).
**Where the real contract tests land:** `loyalty-service`'s own
`LoyaltyRedemptionFlaggedContractTest` (producer side) once scaffolded, and
`notification-service`'s consumer-copy test once it builds the alert-handling path. Signed-off gap
for this wave.
