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
| `OrgUnitCreated/Changed` | org-service | employee, verticals, finance | org_unit_id, type, parent_id, company_id | planned |
| **`EntitlementGranted`** | **entitlement-service** | **shell, all services** | **company_id, module_key** | **LIVE** |
| **`EntitlementRevoked`** | **entitlement-service** | **shell, all services** | **company_id, module_key** | **LIVE** |
| `EmployeeChanged` | employee | verticals | employee_id, company_id, status | planned |
| `AssignmentChanged` | employee | verticals, finance | employee_id, org_unit_id, reporting_to, effective_from/to | planned |
| `MetricPublished` | verticals | employee | metric_key, period, grain, subject_id, value, source_business_id | planned |
| `PeriodSealed` | verticals | employee, finance | business_id, period | planned |
| **`SaleRecorded`** | **restaurant-service** (verticals) | **finance** | **sale_id, company_id, business_id, amount_minor, currency, occurred_at** | **LIVE (M1.4)** |
| `ExpenseRecorded` | verticals | finance | expense_id, company_id, business_id, amount, gl_hint | planned |
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

### `SaleRecorded`

The first live event (M1.4 — the validation slice). Emitted by a vertical when a sale
is recorded; consumed by finance-service to post to the ledger.

- **Producer:** `restaurant-service` (and the other verticals as they ship)
- **Consumers:** `finance-service` (ledger posting + consolidated-revenue read model) — **LIVE (M1.5)**
- **Aggregate type / partition key:** `sale` / `sale_id`
- **Outbox `event_type`:** `SaleRecorded`
- **Schema (producer, source of truth):** `services/restaurant-service/src/main/resources/avro/SaleRecorded.avsc`
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
