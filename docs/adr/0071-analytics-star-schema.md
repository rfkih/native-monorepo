# ADR 0071 — Analytics: a GL-and-event-derived star schema in its own service

- **Status:** Accepted (2026-09-02) — P0 shipped, P1 shipped (this document lands with P1)
- **Deciders:** owner + tech lead
- **Plan of record:** `~/.claude/plans/lexical-cuddling-kettle.md` (approved 2026-09-01, amended
  after design review 2026-09-02 — the amendments are folded in below)

## Context

The console needs an enterprise-grade **Analitik** menu: eight curated dashboards (Ringkasan,
Penjualan, Menu & Produk, Outlet, Karyawan, Pelanggan, Inventori, Keuangan). Today's reporting is
finance's GL-derived statements plus scattered per-service reads; there is no volumetric/shape
store (trends by hour, item mix, staff attribution, RFM) and no place to build one without either
(a) hammering the operational DBs with analytic scans, or (b) re-deriving money figures outside the
GL — the exact anti-pattern [ADR 0065](0065-gl-derived-dashboard-pnl.md) exists to kill.

Designing this surfaced a **pre-existing production bug**: finance-service had **no Debezium
connector at all** — `ConsolidationClosed` and `TrialBalancePublished` were written to its outbox
and relayed nowhere, in every environment including prod, so group consolidation ingested from a
read model that was never fed. Fixed in P0 (`docker/debezium/finance-outbox-connector.json`,
slots/wal-senders 10→20); the connector's `snapshot.mode: initial` replays the retained backlog and
repairs `group_trial_balance`.

## Decision

### 1. A new `analytics-service` (the 12th deployable), fed by events only

Own DB/schema, Kafka-fed fact tables, zero synchronous calls to any business service (rule 2). Star
schema: `fact_gl_day`, `fact_sale_hour`, `fact_sale_item_day`, `fact_staff_day`,
`fact_inventory_day`, `fact_customer` + dimension mirrors — all Auditable + FORCE RLS, additive
`ON CONFLICT` upserts (the V42 `ingredient_usage_day` precedent), money as integer minor units +
ISO-4217 (rule 8). `fact_gl_day`'s nullable `business_id` inside its unique key uses
`UNIQUE NULLS NOT DISTINCT` (plain UNIQUE never matches NULL on conflict — a silent double-count).

### 2. AR-1 — the money rule (the load-bearing decision)

> **analytics-service is NEVER the source of a figure the UI labels with a GL word** (Pendapatan
> bersih, Laba kotor, Laba bersih, HPP, Beban, Kas). The **console** fetches those scalars from
> finance's GL-derived readers — frontend composition, which rule 2 permits (it forbids
> service-to-service sync calls, not the console reading two services). analytics owns volumetric,
> behavioural and **shape** facts: trends, rankings, mix, per-account bars.

ADR 0065's failure mode was two systems computing the same-named number two ways; AR-1 makes that
unrepresentable for every headline figure. "Omzet" (gross sales) legitimately ≠ GL net revenue — a
definition difference a tooltip explains, not a divergence.

### 3. `JournalEntryPosted` from one structural choke point (P0+P1, shipped)

- **P0:** `gl/service/GeneralLedgerWriter.post(entry, companyId)` — the GL's **one persistence
  door**; 29 call sites across 28 writer classes collapsed onto it. The ArchUnit rule
  `onlyTheGeneralLedgerWriterPersistsJournals` forbids any other class from calling a write method
  on `JournalEntryRepository`/`JournalLineRepository` (read-side access stays legal — trial-balance
  aggregation and ADR 0064 supersession lookups need it).
- **P1:** the door emits **`JournalEntryPosted`** (schema in
  `libs/contracts/src/main/resources/avro/JournalEntryPosted.avsc`, catalog entry in
  `docs/EVENT-CATALOG.md`) via the transactional outbox, atomic with the entry. Because persistence
  and emission share the single door, "the 30th posting writer forgot to emit" is structurally
  impossible — that guarantee, not a numeric test, is the ADR 0065 lesson applied one level down.
  `GlOutboxCompletenessTest` (entry-count == event-count, wire lines faithful and balanced) is the
  belt to those suspenders.

Consumer-side idempotency must key on **`journal_entry_id`** (a claim table), never the outbox
event id — a replayed/synthesized event carries a fresh outbox id; the claim key is what makes
backfill possible. `REVERSAL` entries are additive contra rows downstream, never deletes. `period`
is carried verbatim from the entry (payroll posts into periods that are not
`periodOf(occurred_at)`).

### 4. Outbox retention — decided: nightly VPS prune, backup-first, connector-guarded (option b)

`JournalEntryPosted` roughly doubles finance's outbox write volume, and the outbox was never pruned
anywhere. Options considered: (a) a lazy sweep inside `OutboxWriter` — idiomatic (no `@Scheduled`
in this fleet, ADR 0029) but puts a `DELETE` on the hot write path of the fleet's highest-volume
table; (b) a SQL prune in the nightly VPS maintenance; (c) an ops-invoked endpoint. **Chosen: (b)**
— `scripts/prod-outbox-prune.sh`, invoked by `scripts/prod-backup.sh` **after** a successful
backup, so every pruned row already lives in tonight's encrypted archive (and the offsite copies).
Three rails: prune a service's outbox **only while its Debezium connector task is RUNNING** (a
missing/failed connector may mean unrelayed rows — the exact way finance's backlog would have been
destroyed before its P0 snapshot ran), only rows older than `OUTBOX_KEEP_DAYS` (default 30), and
always backup-first. The outbox is a queue, not an archive: replay/backfill (P9) reads the owning
service's **own domain tables**, and the append-only GL regenerates its event stream to inception.

### 5. Curated pages, role-gated, not tier-gated

Eight curated dashboards (no self-service explorer), a new `analytics` nav group gated to
`REPORTS_ROLES` (owner/manager/accountant) at the gateway and in `navGroups.ts`. No new
`FeatureKey`. Recharts (first real use) lives in one lazy `/analytics` chunk, **never** in the
prefetch warmer.

### 6. Scope honesty (what each page can truthfully show)

- **Menu & Produk** is blocked until `SaleLinesRecorded` exists (P7) — no event carries sale lines.
- **Karyawan** is restaurant-only (`sold_by_user_id` is stamped for operator-PIN sales since ADR
  0049 P4; carwash/barbershop never set it), and commission cannot join sales —
  `MetricPublished.subject_id` is an employee_id, `sold_by_user_id` a Keycloak sub, and no event
  bridges the id spaces. Names resolve in the console (no PII on the wire, rule 6).
- **Pelanggan** covers loyalty members only (`loyalty_member_id` is the sole customer key on a POS
  sale) — every retention figure ships with the attach-rate beside it.
- **Outlet** says **"Kontribusi outlet"**, never "Laba bersih per outlet": ~16 posting kinds are
  company-level by domain design, so Σ outlets ≠ company total, permanently. The explicit "Tidak
  dialokasikan (tingkat perusahaan)" row makes `Σ outlets + unallocated = company total` reconcile
  by construction.

## Phases

| Phase | Delivers | Status |
| --- | --- | --- |
| P0 | finance Debezium connector + slots 10→20; `GeneralLedgerWriter` + ArchUnit guard | **shipped** (`ef2ff193`, `dc9dbda3`) |
| P1 | `JournalEntryPosted` (avsc + catalog + contract test), emission from the door, `GlOutboxCompletenessTest`, retention decision + `prod-outbox-prune.sh`, stale `sold_by_user_id` docs corrected, this ADR | **shipped** |
| P2 | analytics-service scaffold, DB/role/compose/port, gateway route, dim tables + listeners | — |
| P3 | `fact_sale_hour` + `fact_register_close_day` + claim tables + read API + chart kit → **Penjualan, Ringkasan** | — |
| P4 | `fact_gl_day` + `FactGlDayMatchesTrialBalanceTest` → **Keuangan** | — |
| P5 | `journal_entry.business_id` (+ backfill via `ledger_posting.source_event_id`) → **Outlet** | — |
| P6 | `fact_staff_day`, `fact_customer` → **Karyawan, Pelanggan** (both partial, disclosed) | — |
| P7 | `SaleLinesRecorded` → `fact_sale_item_day` → **Menu & Produk** | — |
| P8 | ingredient-grain event → **Inventori** | — |
| P9 | replay/backfill (`<Event>.replay` topics only analytics consumes) + rebuild endpoint | — |

## Consequences

- A 12th JVM costs +512m on the prod VPS — **verify free RAM before P2 ships** (the host co-hosts
  Blackheart and has taken a disk-full outage; this is the likeliest reason to revisit the
  separate-service decision).
- finance's outbox volume roughly doubles; retention (above) bounds it. A pruned row stays
  recoverable from the encrypted backups for ~30 days after pruning (14 nightly local + 30
  offsite), i.e. ~60 days from creation — NOT forever; the durable history is the append-only GL
  itself, which regenerates the event stream to inception.
- Every future posting writer feeds analytics automatically; every future GL-word figure keeps
  exactly one source (finance). The invariant tests that keep this honest ship with their phases
  (`FactGlDayMatchesTrialBalanceTest`, `OutletContributionSumsToCompanyTest`).
- `sold_by_user_id` pre-ADR-0049-P4 and ingredient usage pre-restaurant-V42 are genuinely
  unrecoverable; those facts start when their data does — disclosed in the pages' empty states.
