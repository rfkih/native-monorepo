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
| `CompanyCreated` | org-service | entitlement, finance, verticals | company_id, legal_employer_id, base_currency, default_language | planned (M1.2) |
| `OrgUnitCreated/Changed` | org-service | employee, verticals, finance | org_unit_id, type, parent_id, company_id | planned |
| `EntitlementGranted/Revoked` | entitlement | shell, all services | company_id, module_key | planned |
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

### `SaleRecorded`

The first live event (M1.4 — the validation slice). Emitted by a vertical when a sale
is recorded; consumed by finance-service to post to the ledger.

- **Producer:** `restaurant-service` (and the other verticals as they ship)
- **Consumers:** `finance-service` (ledger posting + consolidated-revenue read model)
- **Aggregate type / partition key:** `sale` / `sale_id`
- **Outbox `event_type`:** `SaleRecorded`
- **Schema:** `services/restaurant-service/src/main/resources/avro/SaleRecorded.avsc`
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
