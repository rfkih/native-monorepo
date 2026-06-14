# Event Catalog

> The inter-service contract for Native. **Read this before touching any event.**
> CLAUDE.md rule 7 / the "Never" list: no event may exist without an entry here AND
> a registered Avro schema, and every schema change must be **backward-compatible
> only**.

All events are published through the **transactional outbox** (rule 3) and tailed to
Kafka by Debezium. Consumers are **idempotent** (dedupe by event id/key). Avro is the
wire format; schemas live alongside their producing service
(`<service>/src/main/resources/avro/*.avsc`) and are registered in the Schema
Registry.

---

## Starter event table (planned contracts)

Seeded from ARCHITECTURE.md §5. These are the *planned* inter-service contracts; an
event is only **live** once it has a concrete section below with its registered Avro
schema. Until then a row here is documentation of intent, not a shippable contract.

| Event | Producer | Consumers | Key fields | Status |
|---|---|---|---|---|
| **`CompanyCreated`** | **org-service** | **entitlement, finance, verticals** | **company_id, legal_employer_id, base_currency, default_language** | **LIVE (M1.2)** |
| **`OrgUnitCreated`** | **org-service** | **employee, verticals, finance** | **org_unit_id, type, parent_id, company_id** | **LIVE (#18)** |
| **`OrgUnitChanged`** | **org-service** | **employee, verticals, finance** | **org_unit_id, type, parent_id, company_id** | **LIVE (#18)** |
| **`EntitlementGranted`** | **entitlement-service** | **shell, all services** | **company_id, module_key** | **LIVE** |
| **`EntitlementRevoked`** | **entitlement-service** | **shell, all services** | **company_id, module_key** | **LIVE** |
| **`EmployeeChanged`** | **employee-service** | **verticals** | **employee_id, company_id, status** | **LIVE (#19)** |
| **`AssignmentChanged`** | **employee-service** | **verticals, finance** | **employee_id, org_unit_id, reporting_to, effective_from/to** | **LIVE (#19)** |
| **`MetricPublished`** | **carwash-service** (verticals) | **employee** | **metric_key, period, grain, subject_id, value, source_business_id** | **LIVE (#20)** |
| `PeriodSealed` | verticals | employee, finance | business_id, period | planned |
| **`SaleRecorded`** | **restaurant-service + carwash-service** (verticals) | **finance** | **sale_id, company_id, business_id, amount_minor, currency, occurred_at** | **LIVE (M1.4 / #20)** |
| **`ExpenseRecorded`** | **verticals** | **finance** | **expense_id, company_id, business_id, amount_minor, currency, gl_hint, occurred_at** | **LIVE (finance consumer #21)** |
| `PayrollPosted` | employee | finance | payroll_run_id, company_id, period | planned |
| `LaborCostAllocated` | employee | finance | employee_id, company_id, outlet_id, amount, period | planned |
| `ConsolidationClosed` | finance | shell, notification | company_id (or group_id), period | planned |

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
tree — `business_unit > branch > outlet > team`) is created, including the company's
root business unit created during the company bootstrap (#18 — the full org tree).
Consumed by employee-service, the verticals, and finance-service so each caches its
slice of the org tree without a synchronous call (rule 2).

- **Producer:** `org-service`
- **Consumers:** `employee-service` (anchors assignments on the org tree),
  the verticals (local staff/org read model), `finance-service` (dimensional ledger
  org dimensions).
- **Aggregate type / partition key:** `org_unit` / `org_unit_id`
- **Outbox `event_type`:** `OrgUnitCreated`
- **Schema:** `services/org-service/src/main/resources/avro/OrgUnitCreated.avsc`
- **Full name:** `id.co.nativeapp.events.org.OrgUnitCreated`

The org tree is strictly nested and enforced in the `OrgUnit` aggregate: a `business_unit`
is a top-level node (`parent_id` null); a `branch` hangs under a `business_unit`; an
`outlet` under a `branch`; a `team` under an `outlet`. `parent_id` is therefore a nullable
union (`["null","string"]`, default `null`).

**Key fields** (ARCHITECTURE.md §5: `org_unit_id`, `type`, `parent_id`, `company_id`)

| Field | Avro type | Meaning |
|---|---|---|
| `org_unit_id` | `string` | The org_unit aggregate id (UUID as string); the partition key |
| `company_id` | `string` | The owning tenant / company id (UUID as string) |
| `type` | `string` | The org-unit kind: `business_unit` \| `branch` \| `outlet` \| `team` |
| `parent_id` | `["null","string"]` (default `null`) | The parent org_unit id, or null for a top-level node |
| `legal_employer_id` | `string` | The legal employer this node belongs to (UUID as string) |
| `name` | `string` | The org-unit display name |

**Avro schema**

```json
{
  "type": "record",
  "name": "OrgUnitCreated",
  "namespace": "id.co.nativeapp.events.org",
  "doc": "Emitted by org-service when an org_unit (a node in the company's business_unit > branch > outlet > team tree) is created; consumed by employee-service, the verticals, and finance-service so each can cache its slice of the org tree without a synchronous call (rule 2). Key fields per ARCHITECTURE.md §5: org_unit_id, type, parent_id, company_id.",
  "fields": [
    {"name": "org_unit_id", "type": "string", "doc": "The org_unit aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string)."},
    {"name": "type", "type": "string", "doc": "The org-unit kind: business_unit | branch | outlet | team."},
    {"name": "parent_id", "type": ["null", "string"], "default": null, "doc": "The parent org_unit id (UUID as string), or null for a top-level node (a business_unit)."},
    {"name": "legal_employer_id", "type": "string", "doc": "The legal employer this node belongs to (UUID as string)."},
    {"name": "name", "type": "string", "doc": "The org-unit display name."}
  ]
}
```

**Compatibility.** Backward-compatible only (add optional fields with a default; never a
new required field without a default, never remove/rename/retype). Enforced by
`OrgUnitEventContractTest` (parse + `AvroSerde` round-trip + back-compat for-change /
against-break).

### `OrgUnitChanged`

Emitted by org-service when an `org_unit` is **renamed**, **moved** to a new parent, or
**deactivated** via `PATCH /api/v1/org-units/{orgUnitId}` (#18). One event per effective
change. Consumed by the same set as `OrgUnitCreated` to update their cached slice of the
org tree; it carries the node's new state so a consumer applies it idempotently.

- **Producer:** `org-service`
- **Consumers:** `employee-service`, the verticals, `finance-service` (same as
  `OrgUnitCreated`).
- **Aggregate type / partition key:** `org_unit` / `org_unit_id`
- **Outbox `event_type`:** `OrgUnitChanged`
- **Schema:** `services/org-service/src/main/resources/avro/OrgUnitChanged.avsc`
- **Full name:** `id.co.nativeapp.events.org.OrgUnitChanged`

**Key fields** (ARCHITECTURE.md §5: `org_unit_id`, `type`, `parent_id`, `company_id`)

| Field | Avro type | Meaning |
|---|---|---|
| `org_unit_id` | `string` | The org_unit aggregate id (UUID as string); the partition key |
| `company_id` | `string` | The owning tenant / company id (UUID as string) |
| `type` | `string` | The org-unit kind (immutable once created) |
| `parent_id` | `["null","string"]` (default `null`) | The CURRENT parent after the change, or null |
| `change_kind` | `string` | What changed: `RENAMED` \| `MOVED` \| `DEACTIVATED` |
| `name` | `string` | The org-unit display name after the change |
| `active` | `boolean` | Whether the node is still active after the change |

**Avro schema**

```json
{
  "type": "record",
  "name": "OrgUnitChanged",
  "namespace": "id.co.nativeapp.events.org",
  "doc": "Emitted by org-service when an org_unit is renamed, moved to a new parent, or deactivated; consumed by employee-service, the verticals, and finance-service to update their cached slice of the org tree. Key fields per ARCHITECTURE.md §5: org_unit_id, type, parent_id, company_id. Carries the new state so a consumer can apply it idempotently.",
  "fields": [
    {"name": "org_unit_id", "type": "string", "doc": "The org_unit aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant / company id (UUID as string)."},
    {"name": "type", "type": "string", "doc": "The org-unit kind: business_unit | branch | outlet | team (immutable once created)."},
    {"name": "parent_id", "type": ["null", "string"], "default": null, "doc": "The CURRENT parent org_unit id (UUID as string) after the change, or null for a top-level node."},
    {"name": "change_kind", "type": "string", "doc": "What changed: RENAMED | MOVED | DEACTIVATED."},
    {"name": "name", "type": "string", "doc": "The org-unit display name after the change."},
    {"name": "active", "type": "boolean", "doc": "Whether the node is still active after the change (false after a deactivation)."}
  ]
}
```

**Compatibility.** Backward-compatible only, enforced by `OrgUnitEventContractTest`.

### `SaleRecorded`

The first live event (M1.4 — the validation slice). Emitted by a vertical when a sale
is recorded; consumed by finance-service to post to the ledger.

- **Producer:** `restaurant-service` **and `carwash-service`** (#20 — the 2nd vertical; a recorded
  wash emits `SaleRecorded` with `business_id` = the carwash outlet, so finance consolidates carwash
  revenue alongside restaurant), and the other verticals as they ship.
- **Consumers:** `finance-service` (ledger posting + consolidated-revenue read model) — **LIVE (M1.5)**
- **Aggregate type / partition key:** `sale` / `sale_id`
- **Outbox `event_type`:** `SaleRecorded`
- **Schema (producer, source of truth):** `services/restaurant-service/src/main/resources/avro/SaleRecorded.avsc`
- **Schema (carwash producer copy):** `services/carwash-service/src/main/resources/avro/SaleRecorded.avsc` — byte-identical to the restaurant producer schema (same full name); carwash's `SaleRecordedContractTest` asserts the copy stays backward-compatible with the producer schema (rule 7), so finance reads carwash washes through the very same consumer path.
- **Schema (finance consumer copy):** `services/finance-service/src/main/resources/avro/SaleRecorded.avsc` — finance owns its own consumer view of the contract; the finance `SaleRecordedContractTest` asserts the copy stays backward-compatible with the producer schema (rule 7). The finance consumer reads the outbox payload as **raw Avro bytes** via `libs/events AvroSerde` (no Schema Registry serde), deduping by the event UUID (`ProcessedEventStore`) so a re-delivery never double-posts.
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
| `amount_minor` | `long` | Amount in the currency's minor units — never a float |
| `currency` | `string` | ISO-4217 currency code (e.g. `IDR`, `USD`) |
| `occurred_at` | `long` (`timestamp-millis`) | When the sale occurred, epoch millis UTC |

**Avro schema**

```json
{
  "type": "record",
  "name": "SaleRecorded",
  "namespace": "id.co.nativeapp.events.restaurant",
  "doc": "Emitted by a vertical (restaurant-service) when a sale is recorded; consumed by finance-service to post to the ledger. Money is an integer minor-units amount plus an ISO-4217 currency code, never a float (CLAUDE.md rule 8).",
  "fields": [
    {"name": "sale_id", "type": "string", "doc": "The sale aggregate id (UUID as string); also the Kafka partition key."},
    {"name": "company_id", "type": "string", "doc": "The owning tenant (UUID as string)."},
    {"name": "business_id", "type": "string", "doc": "The originating business unit (UUID as string)."},
    {"name": "amount_minor", "type": "long", "doc": "Amount in the currency's minor units (e.g. cents for USD, whole rupiah for IDR). Never a float."},
    {"name": "currency", "type": "string", "doc": "ISO-4217 currency code, e.g. IDR or USD."},
    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "When the sale occurred, epoch millis (UTC)."}
  ]
}
```

**Compatibility.** Only backward-compatible evolution is allowed: add fields with a
default (e.g. an optional `channel` as `["null","string"]` with `default: null`).
Never add a required field without a default, never remove or rename a field, never
change a field's type. The contract test
(`SaleRecordedContractTest`) enforces this — it asserts the schema is
backward-compatible with itself and with an added-optional-field variant, and rejects
a new required field without a default.

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

### `MetricPublished`

Emitted by a vertical when an operational metric is produced (#20 — carwash declares the first live
metric contract). Consumed by employee-service for variable pay / commission. Each vertical declares
its **metric contract**: which `metric_key`s it emits, at which grains (`employee` | `shift` |
`outlet`). The validation layer on the **consumer / employee side** rejects a commission rule
requesting a grain the vertical cannot emit; the producing vertical only DECLARES the contract and
EMITS against it.

- **Producer:** `carwash-service` (and the other verticals as they ship). carwash declares, at the
  `outlet` grain: `wash_count` (a count of washes) and `upsell_amount` (upsell revenue in the wash
  currency's minor units). On recording a wash it emits one `MetricPublished` per declared metric,
  via the transactional outbox in the same transaction as the wash + `SaleRecorded`.
- **Consumers:** `employee-service` (variable pay / commission).
- **Aggregate type / partition key:** `metric` / `source_business_id` (the carwash outlet)
- **Outbox `event_type`:** `MetricPublished`
- **Schema:** `services/carwash-service/src/main/resources/avro/MetricPublished.avsc`
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
