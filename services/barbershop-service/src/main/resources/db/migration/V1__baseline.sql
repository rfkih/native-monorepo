-- barbershop-service baseline — Phase 2 of the POS-parity program (ADR 0024): the 3rd vertical,
-- a copy-with-rename of carwash-service's shipped POS shape. One baseline folds in everything a new
-- service needs on day one (outbox WITH traceparent from the start — the carwash V1+V3 lesson):
--
--   * outbox / processed_event   — the transactional-outbox + idempotent-consumer dedupe machinery.
--   * entitlement_projection     — the LOCAL entitlement read model: (company_id, module_key) ->
--                                  entitled bool, kept current by the EntitlementGranted /
--                                  EntitlementRevoked consumer; every ticket write is gated on the
--                                  "barbershop" module.
--   * staff                      — the LOCAL staff read model: employee_id -> {org_unit_id, active},
--                                  kept current by the EmployeeChanged / AssignmentChanged consumer.
--   * service_item / service_addon / staff_profile — the POS catalog: priced services (haircut,
--                                  shave, coloring…), addons (hot towel…), and the tenant's barber
--                                  directory. service_item ALSO carries a duration_minutes column
--                                  RESERVED for a future appointments app — persisted and exposed
--                                  read-only in responses, settable on create/patch, otherwise
--                                  unused by this POS-checkout slice.
--   * tax_charge_rule            — versioned + effective-dated tax/service-charge configuration
--                                  (seeded with one illustrative VAT_BARBERSHOP row below).
--   * barbershop_ticket / barbershop_ticket_line / barbershop_payment — the checkout aggregate: a
--                                  one-shot ticket (no park/tabs/bills/KDS), its priced lines
--                                  (SERVICE | ADDON), and its tender(s). staff_profile_id is
--                                  MANDATORY here (unlike carwash's optional washer) — every cut has
--                                  a barber.
--   * user_outlet_assignment_ref — the local cache of org-service user -> outlet assignments backing
--                                  outlet-scoping enforcement at checkout.
--
-- The app connects as a NON-superuser role (without BYPASSRLS), so RLS is genuinely enforced;
-- FORCE ROW LEVEL SECURITY makes the policy apply even to the table owner / migration role, so no
-- connection silently bypasses tenant isolation (CLAUDE.md rule 5 — defense in depth).
-- current_setting('app.current_tenant', true) returns NULL when the GUC is unset, so with no
-- tenant bound every policy predicate is false -> fail closed.

-- ---------------------------------------------------------------------------
-- transactional outbox (consumed by Debezium / StubRelay) — traceparent folded in from day one
-- ---------------------------------------------------------------------------
-- Columns match libs/events OutboxRecord / OutboxWriter exactly. published_at is set by the relay
-- (ignored by Debezium). traceparent (ADR 0010 #13 — outbox->Kafka trace continuity) carries the W3C
-- traceparent string captured at write time; Debezium maps it to a Kafka header so the consumer can
-- restore the trace context and emit a child span rather than a new root — nullable: rows written
-- when no span is in scope carry NULL, which consumers treat as "start a new root span". NOT
-- Auditable and NOT under RLS: it is infrastructure the relay tails, its rows already carry
-- company_id for downstream tenant scoping, and the writer always runs inside the ticket's
-- RLS-scoped transaction. PII is NEVER written to a payload or header (rule 6).
CREATE TABLE outbox (
    id             UUID                     NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(255)             NOT NULL,
    aggregate_id   VARCHAR(255)             NOT NULL,
    event_type     VARCHAR(255)             NOT NULL,
    payload        BYTEA                    NOT NULL,
    headers        TEXT                     NULL,
    company_id     UUID                     NOT NULL,
    occurred_at    TIMESTAMPTZ              NOT NULL,
    published_at   TIMESTAMPTZ              NULL,
    traceparent    VARCHAR(64)              NULL
);

-- The relay polls for UNPUBLISHED rows in occurrence order. A PARTIAL index (WHERE published_at IS
-- NULL) indexes only the rows the relay still has to ship, so it stays tiny.
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- processed_event (the idempotent-consumer dedupe store)
-- ---------------------------------------------------------------------------
-- libs/events ProcessedEventStore records each handled event UUID here and skips duplicates
-- (INSERT ... ON CONFLICT DO NOTHING), so a re-delivered EntitlementGranted/Revoked or
-- EmployeeChanged/AssignmentChanged is applied at most once (rule 3). The claim runs INSIDE the
-- same transaction as the projection upsert, so dedupe and side effects commit together. Not
-- Auditable and not under RLS: keyed solely by the globally-unique event id, not tenant-scoped data.
CREATE TABLE processed_event (
    event_id     UUID                     NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- ---------------------------------------------------------------------------
-- entitlement_projection (the LOCAL entitlement read model)
-- ---------------------------------------------------------------------------
-- (company_id, module_key) -> entitled bool. Maintained by the EntitlementGranted / Revoked
-- consumer (idempotent via processed_event). It backs the DB-backed EntitlementLoader the shared
-- libs/entitlement-check cache consults on a miss; every ticket write is gated on the "barbershop"
-- module.
--
-- Like the other app-maintained read models in the codebase (finance's consolidated_revenue,
-- employee's org_unit_projection), it is Auditable + RLS (NOT a Debezium-CDC-derived projection),
-- so the rule-4 Auditable + rule-5 RLS guarantees apply uniformly. The consumer writes it inside a
-- TenantContext scope bound to the EVENT's company_id, so the WITH CHECK passes.
CREATE TABLE entitlement_projection (
    id          UUID         NOT NULL PRIMARY KEY,

    -- The module this projection row is for (e.g. "barbershop").
    module_key  VARCHAR(64)  NOT NULL,

    -- Whether the company is currently entitled to the module (true on Granted, false on Revoked).
    entitled    BOOLEAN      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    -- At most one projection row per company + module; the consumer upserts this key.
    CONSTRAINT uq_entitlement_projection_company_module UNIQUE (company_id, module_key)
);

ALTER TABLE entitlement_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE entitlement_projection FORCE ROW LEVEL SECURITY;

CREATE POLICY entitlement_projection_tenant_isolation ON entitlement_projection
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- staff (the LOCAL staff read model)
-- ---------------------------------------------------------------------------
-- employee_id -> {org_unit_id, active}. Maintained by the EmployeeChanged / AssignmentChanged
-- consumer (idempotent via processed_event) so the vertical knows its staff (rule 2 — a cached
-- read model, never a sync call). EmployeeChanged carries the employee + status (active);
-- AssignmentChanged carries the org_unit the employee is assigned to. NO PII is ever projected
-- here — the events carry only employee_id / company_id / status / org_unit dimensions (rule 6).
--
-- App-maintained read model -> Auditable + RLS (NOT a Debezium-CDC-derived projection). The
-- consumer writes it inside a TenantContext scope bound to the EVENT's company_id.
CREATE TABLE staff (
    employee_id   UUID         NOT NULL PRIMARY KEY,

    -- The org_unit the employee is currently assigned to (from AssignmentChanged), or NULL until an
    -- assignment has been seen (EmployeeChanged may arrive first).
    org_unit_id   UUID         NULL,

    -- Whether the employee is ACTIVE (from EmployeeChanged status). Defaults true on a first
    -- assignment-only sighting; corrected by the EmployeeChanged replay.
    active        BOOLEAN      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(255) NOT NULL,
    version       BIGINT       NOT NULL,
    company_id    VARCHAR(64)  NOT NULL
);

ALTER TABLE staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff FORCE ROW LEVEL SECURITY;

CREATE POLICY staff_tenant_isolation ON staff
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- service_item — the barbershop's priced offerings (haircut, shave, coloring…)
-- ---------------------------------------------------------------------------
CREATE TABLE service_item (
    id                UUID         NOT NULL PRIMARY KEY,

    -- The barbershop outlet (org_unit) this service is offered at.
    business_id       UUID         NOT NULL,

    name              VARCHAR(255) NOT NULL,
    description       VARCHAR(1024) NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    price_minor       BIGINT       NOT NULL,
    currency          CHAR(3)      NOT NULL,

    -- RESERVED for a future appointments app: how long this service typically takes. Persisted and
    -- exposed read-only in responses, settable on create/patch — NOT consumed by this POS-checkout
    -- slice (pricing/ticketing never reads it).
    duration_minutes  INT          NULL,

    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order     INT          NOT NULL DEFAULT 0,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_service_item_price_minor_nonneg CHECK (price_minor >= 0),
    CONSTRAINT ck_service_item_duration_minutes_positive
        CHECK (duration_minutes IS NULL OR duration_minutes > 0)
);

ALTER TABLE service_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_item FORCE ROW LEVEL SECURITY;

CREATE POLICY service_item_tenant_isolation ON service_item
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot access path: the checkout picker lists active services for an outlet, ordered for display.
-- Partial index (WHERE active) keeps it small — retired services fall out of the index entirely.
CREATE INDEX idx_service_item_active
    ON service_item (company_id, business_id)
    WHERE active = TRUE;

-- ---------------------------------------------------------------------------
-- service_addon — the upsell catalog (hot towel, etc.); identical shape to service_item minus
-- duration_minutes (an addon has no appointment-slot duration of its own).
-- ---------------------------------------------------------------------------
CREATE TABLE service_addon (
    id             UUID         NOT NULL PRIMARY KEY,

    -- The barbershop outlet (org_unit) this addon is offered at.
    business_id    UUID         NOT NULL,

    name           VARCHAR(255) NOT NULL,
    description    VARCHAR(1024) NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    price_minor    BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL,

    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order  INT          NOT NULL DEFAULT 0,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_service_addon_price_minor_nonneg CHECK (price_minor >= 0)
);

ALTER TABLE service_addon ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_addon FORCE ROW LEVEL SECURITY;

CREATE POLICY service_addon_tenant_isolation ON service_addon
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Same access path as service_item: the checkout upsell picker for an outlet.
CREATE INDEX idx_service_addon_active
    ON service_addon (company_id, business_id)
    WHERE active = TRUE;

-- ---------------------------------------------------------------------------
-- staff_profile — the tenant's barber directory
-- ---------------------------------------------------------------------------
-- display_label is tenant-entered (e.g. "Budi", "Chair 2 crew") — the events this service consumes
-- and publishes carry no PII (rule 6), so this is deliberately a free-text label the tenant chooses,
-- not an employee name sourced from HR data. employee_id OPTIONALLY links to the local `staff` read
-- model row (employee_id -> {org_unit_id, active}, kept current by the EmployeeChanged /
-- AssignmentChanged consumer) so that a ticket recorded against this profile can attribute the
-- sales_amount@employee commission metric to the barber, without this service ever holding or
-- displaying employee PII itself. Column shape is IDENTICAL to carwash-service's staff_profile.
CREATE TABLE staff_profile (
    id             UUID         NOT NULL PRIMARY KEY,

    -- The barbershop outlet (org_unit) this barber profile belongs to.
    business_id    UUID         NOT NULL,

    -- Tenant-entered label identifying the barber at the till. No PII (rule 6).
    display_label  VARCHAR(128) NOT NULL,

    -- Optional link to the local staff read model (`staff.employee_id`) for commission
    -- attribution. No FK: `staff` rows arrive asynchronously via EmployeeChanged/AssignmentChanged
    -- and may not exist yet when a profile is created; the link is opportunistic, not enforced.
    employee_id    UUID         NULL,

    active         BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_staff_profile_company_business_label
        UNIQUE (company_id, business_id, display_label)
);

ALTER TABLE staff_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_profile FORCE ROW LEVEL SECURITY;

CREATE POLICY staff_profile_tenant_isolation ON staff_profile
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot access path: the checkout barber picker lists active profiles for an outlet.
CREATE INDEX idx_staff_profile_active
    ON staff_profile (company_id, business_id)
    WHERE active = TRUE;

-- ---------------------------------------------------------------------------
-- tax_charge_rule — per-tenant tax and service-charge configuration (POS-parity pricing)
-- ---------------------------------------------------------------------------
-- Column-for-column mirror of carwash-service's tax_charge_rule — same resolver contract (ORDER BY
-- rule_version DESC picks the highest-version active row effective at a given date), same
-- provenance/RLS/audit shape, so the same shared resolver strategy works across verticals.
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA
-- ============================================================================================================================
-- The rule seeded at the bottom of this migration is an INTENTIONAL ILLUSTRATIVE PLACEHOLDER.
--
-- WHAT IS NOT CONFIRMED:
--   1. REGIME: Whether barbershop / personal-care services are subject to national PPN (VAT,
--      currently 11%), a regional indirect levy, or some combination is UNCONFIRMED. The seeded key
--      "VAT_BARBERSHOP" and rate 1100 bp (11%) are illustrative only — do NOT assert this as the
--      confirmed regime.
--   2. SERVICE CHARGE: Barbershop tickets carry a service_charge_minor column, but NO
--      SERVICE_CHARGE rule row is seeded here — the resolver's no-rule fall-through yields zero, so
--      no ticket accrues a service charge until an SME-reviewed rule is inserted for this key.
--   3. TAX BASE COMPOSITION: service_charge_in_tax_base is set TRUE on the seeded row purely for
--      schema/resolver consistency with carwash-service; it has no observable effect today since
--      no service charge is ever computed without a SERVICE_CHARGE rule. If/when a barbershop
--      service charge rule is introduced, this flag must be revisited by an SME at that time.
--   4. RATE: 11% is an illustrative round number aligned to the current national PPN rate for
--      development only — NOT a confirmed statement that barbershop/personal-care services fall
--      under PPN vs a regional levy.
--
-- To replace with a real rule (NO code change required):
--   INSERT INTO tax_charge_rule (..., rule_version, rate_bp, provenance, source_note, effective_from)
--       VALUES (..., 'OFFICIAL-2027.1', <real_rate>, 'OFFICIAL', '<SME sign-off note>', <date>);
-- The resolver (ORDER BY rule_version DESC) prefers the higher-version row automatically.
-- The ILLUSTRATIVE row remains as an audit trail and does NOT need to be deleted.
-- ============================================================================================================================

CREATE TABLE tax_charge_rule (
    id                          UUID         NOT NULL PRIMARY KEY,
    rule_key                    VARCHAR(64)  NOT NULL,
    rule_version                VARCHAR(64)  NOT NULL,

    -- Rate in basis points (1 bp = 0.01%; 1100 bp = 11%). NEVER a float (rule 8).
    rate_bp                     BIGINT       NOT NULL,

    -- Whether service charge is included in the tax base before applying the tax rate.
    -- TRUE  → taxBase = taxableBase + serviceCharge
    -- FALSE → taxBase = taxableBase only
    -- SME-gated: correct treatment depends on jurisdiction and contract.
    service_charge_in_tax_base  BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Provenance: ILLUSTRATIVE_PLACEHOLDER or OFFICIAL (CHECK constraint, no default).
    -- A checkout resolving an ILLUSTRATIVE row sets uses_illustrative_rules=true on SaleRecorded.
    provenance                  VARCHAR(32)  NOT NULL,

    -- Human-readable note: regulation reference, SME sign-off date, etc. Mandatory.
    source_note                 TEXT         NOT NULL,

    -- ISO-4217 currency this rule applies to (e.g. 'IDR').
    currency                    CHAR(3)      NOT NULL,

    effective_from              DATE         NOT NULL,
    effective_to                DATE         NOT NULL DEFAULT DATE '9999-12-31',
    active                      BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at                  TIMESTAMPTZ  NOT NULL,
    created_by                  VARCHAR(255) NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    updated_by                  VARCHAR(255) NOT NULL,
    version                     BIGINT       NOT NULL,
    company_id                  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_tax_charge_rule_provenance
        CHECK (provenance IN ('ILLUSTRATIVE_PLACEHOLDER', 'OFFICIAL')),
    CONSTRAINT ck_tax_charge_rule_rate_bp_nonneg
        CHECK (rate_bp >= 0),
    CONSTRAINT uq_tax_charge_rule_key_version
        UNIQUE (company_id, rule_key, rule_version)
);

ALTER TABLE tax_charge_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE tax_charge_rule FORCE ROW LEVEL SECURITY;

CREATE POLICY tax_charge_rule_tenant_isolation ON tax_charge_rule
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Resolution index: find the highest-version active row effective at a given date.
CREATE INDEX idx_tax_charge_rule_resolution
    ON tax_charge_rule (company_id, rule_key, effective_from, effective_to)
    WHERE active = TRUE;

-- ============================================================================================================================
-- ILLUSTRATIVE SEED — for the demo tenant (11111111-1111-1111-1111-111111111111) only.
-- The row below is provenance='ILLUSTRATIVE_PLACEHOLDER'. The rate and regime are NOT verified.
-- No SERVICE_CHARGE row is seeded (see note 2 above): the no-rule fall-through yields zero.
-- ============================================================================================================================

-- tax_charge_rule has FORCE ROW LEVEL SECURITY. Migrations run as the DB owner (SUPERUSER or
-- BYPASSRLS-granted role), so we must set the tenant GUC for the INSERT's WITH CHECK to pass.
-- SET LOCAL applies only to this transaction (the Flyway migration's implicit transaction). This is
-- a fresh INSERT (not an UPDATE against existing rows), so the RLS-migration-backfill gotcha — a
-- FORCE-RLS UPDATE silently matching 0 rows without a NO FORCE/FORCE wrap — does not apply here;
-- SET LOCAL alone is sufficient, exactly as carwash-service V5 does it.
SET LOCAL app.current_tenant = '11111111-1111-1111-1111-111111111111';

-- VAT_BARBERSHOP: 11% (1100 bp) — illustrative barbershop / personal-care indirect-tax rate.
-- SME NOTE: The applicable regime for barbershop / personal-care services (national PPN 11% vs a
-- regional indirect levy) is UNCONFIRMED and must be verified by an accounting + tax SME before
-- going live. The rate happens to equal the current national PPN rate, but this is NOT an assertion
-- that PPN is the correct regime.
INSERT INTO tax_charge_rule (
    id, rule_key, rule_version, rate_bp, service_charge_in_tax_base,
    provenance, source_note, currency,
    effective_from, effective_to, active,
    created_at, created_by, updated_at, updated_by, version, company_id
) VALUES (
    gen_random_uuid(),
    'VAT_BARBERSHOP',
    'ILLUSTRATIVE-2026.1',
    1100,        -- 11% illustrative; NOT confirmed as national PPN nor a regional levy
    TRUE,        -- service charge in tax base: inert today (no SERVICE_CHARGE rule seeded); SME-gated
    'ILLUSTRATIVE_PLACEHOLDER',
    'ILLUSTRATIVE ONLY — not verified by DJP or a local/regional tax authority. Whether barbershop / '
    'personal-care services fall under national PPN (currently 11%) or a regional indirect levy is '
    'UNCONFIRMED and must be confirmed by an accounting + tax SME before any regulatory use. No '
    'service-charge rule is seeded for barbershop (no-rule fall-through = zero). Seeded by V1 for '
    'development demo (Native barbershop POS-parity pricing, ADR 0024).',
    'IDR',
    DATE '2026-01-01',
    DATE '9999-12-31',
    TRUE,
    now(), 'system-migration', now(), 'system-migration', 0,
    '11111111-1111-1111-1111-111111111111'
);

-- ---------------------------------------------------------------------------
-- barbershop_ticket — the checkout aggregate
-- ---------------------------------------------------------------------------
-- Mirrors carwash-service's carwash_ticket column-for-column, with the domain differences ADR 0024
-- calls for: `chair` replaces `bay` and is NULLABLE (a barbershop may run without assigned chairs),
-- there is NO vehicle_plate, and `staff_profile_id` is MANDATORY (NOT NULL) — every cut has a
-- barber, so barber attribution cannot be skipped at checkout. `barber_employee_id` replaces
-- `washer_employee_id` as the snapshot of the profile's linked employee id (the LINK itself stays
-- optional; the employee-grain sales_amount metric is skipped when unlinked).
CREATE TABLE barbershop_ticket (
    id                      UUID         NOT NULL PRIMARY KEY,

    -- The barbershop outlet (org_unit) this ticket was opened at; the SaleRecorded business_id.
    business_id             UUID         NOT NULL,

    -- The chair it ran on (free text, e.g. "chair-1"). Nullable — chairs are optional.
    chair                   VARCHAR(64)  NULL,

    -- MANDATORY barber attribution: the staff_profile (barber) selected at checkout. NOT NULL —
    -- every cut has a barber (400 problem when missing at the API boundary).
    staff_profile_id        UUID         NOT NULL,

    -- Snapshot of that profile's linked employee_id at the moment of checkout (the profile's link
    -- may change later; the ticket keeps the value that was true when commission attribution
    -- happened). The LINK stays optional — this snapshot may be NULL when the profile is unlinked.
    barber_employee_id      UUID         NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float (rule 8). Pricing
    -- breakdown: subtotal (sum of line prices) less discount, plus service charge, plus tax, equals
    -- total — enforced by the CHECK constraint below.
    subtotal_minor          BIGINT       NOT NULL,
    discount_minor          BIGINT       NOT NULL DEFAULT 0,
    service_charge_minor    BIGINT       NOT NULL,
    tax_minor               BIGINT       NOT NULL,
    total_minor             BIGINT       NOT NULL,
    currency                CHAR(3)      NOT NULL,

    -- The tax_charge_rule rule_version resolved at checkout, NULL when no rule resolved (zero tax
    -- via fall-through). uses_illustrative_rules mirrors carwash: TRUE when the resolved rule (if
    -- any) carries provenance='ILLUSTRATIVE_PLACEHOLDER', propagated onto SaleRecorded.
    tax_rule_version        VARCHAR(64)  NULL,
    uses_illustrative_rules BOOLEAN      NOT NULL,

    -- The recorded sale this ticket produced. Revenue is recognised AT CAPTURE (ADR 0006): a cash
    -- checkout captures synchronously and stamps this in the same transaction; a digital tender
    -- (QRIS/CARD) leaves it NULL at checkout and stamps it when the pending payment is captured.
    -- An abandoned digital ticket therefore never has a sale.
    sale_id                 UUID         NULL,

    occurred_at             TIMESTAMPTZ  NOT NULL,

    -- Producer idempotency: exactly one ticket per (company, client request id). A retried checkout
    -- resolves to the same row -> exactly one SaleRecorded + one MetricPublished.
    idempotency_key         TEXT         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at              TIMESTAMPTZ  NOT NULL,
    created_by              VARCHAR(255) NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    updated_by              VARCHAR(255) NOT NULL,
    version                 BIGINT       NOT NULL,
    company_id              VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_barbershop_ticket_company_idempotency UNIQUE (company_id, idempotency_key),

    -- Pricing must reconcile: subtotal - discount + service charge + tax = total.
    CONSTRAINT ck_barbershop_ticket_total_balances
        CHECK (subtotal_minor - discount_minor + service_charge_minor + tax_minor = total_minor)
);

ALTER TABLE barbershop_ticket ENABLE ROW LEVEL SECURITY;
ALTER TABLE barbershop_ticket FORCE ROW LEVEL SECURITY;

CREATE POLICY barbershop_ticket_tenant_isolation ON barbershop_ticket
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- barbershop_ticket_line — one row per priced item (service or addon), snapshot at checkout time.
-- ---------------------------------------------------------------------------
CREATE TABLE barbershop_ticket_line (
    id             UUID         NOT NULL PRIMARY KEY,
    ticket_id      UUID         NOT NULL REFERENCES barbershop_ticket (id),
    business_id    UUID         NOT NULL,

    -- SERVICE (service_item) or ADDON (service_addon).
    item_type      VARCHAR(16)  NOT NULL,

    -- The catalog row this line was priced from (service_item.id or service_addon.id, per
    -- item_type). Not a FK: the two catalog tables are disjoint, and the line is a checkout-time
    -- snapshot that must remain even if the catalog row is later retired.
    item_id        UUID         NOT NULL,

    -- Snapshot of the catalog item's name at checkout time (receipt-reproducible).
    name           VARCHAR(255) NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float. Snapshot of the
    -- catalog item's price_minor at checkout time (the catalog price may change later).
    price_minor    BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL,

    qty            INT          NOT NULL DEFAULT 1,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_barbershop_ticket_line_item_type CHECK (item_type IN ('SERVICE', 'ADDON')),
    CONSTRAINT ck_barbershop_ticket_line_qty_positive CHECK (qty > 0)
);

ALTER TABLE barbershop_ticket_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE barbershop_ticket_line FORCE ROW LEVEL SECURITY;

CREATE POLICY barbershop_ticket_line_tenant_isolation ON barbershop_ticket_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot access path: the lines for a ticket (receipt rendering, checkout recompute).
CREATE INDEX idx_barbershop_ticket_line_ticket
    ON barbershop_ticket_line (company_id, ticket_id);

-- ---------------------------------------------------------------------------
-- barbershop_payment — one row per tender against a ticket.
-- ---------------------------------------------------------------------------
-- Mirrors carwash-service's carwash_payment column types where they overlap: cash captures
-- synchronously (status CAPTURED, with tendered + change); a digital tender starts PENDING
-- (provider_pending = TRUE — the flagged-data marker) until captured, so an abandoned digital
-- tender yields no revenue.
CREATE TABLE barbershop_payment (
    id                UUID         NOT NULL PRIMARY KEY,
    ticket_id         UUID         NOT NULL REFERENCES barbershop_ticket (id),
    business_id       UUID         NOT NULL,

    -- CASH | QRIS | CARD, mirroring carwash's TenderType.
    tender_type       VARCHAR(16)  NOT NULL,

    -- PENDING | CAPTURED | VOIDED | REFUNDED | PARTIALLY_REFUNDED | ABANDONED | FAILED,
    -- mirroring carwash's Payment.Status.
    status            VARCHAR(24)  NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    amount_minor      BIGINT       NOT NULL,
    currency          CHAR(3)      NOT NULL,

    -- Cash only: the physical cash handed over, and the change returned. NULL for digital tenders.
    -- change_minor is never negative (CHECK below).
    tendered_minor    BIGINT       NULL,
    change_minor      BIGINT       NULL,

    -- Provider transaction reference; NULL for cash.
    provider_ref      VARCHAR(255) NULL,

    -- The flagged-data marker: TRUE for any digital tender until a real PSP adapter lands; cash is
    -- always FALSE. Never present a provider_pending payment to a user as settled.
    provider_pending  BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_barbershop_payment_tender_type CHECK (tender_type IN ('CASH', 'QRIS', 'CARD')),

    CONSTRAINT ck_barbershop_payment_status CHECK (status IN (
        'PENDING', 'CAPTURED', 'VOIDED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'ABANDONED', 'FAILED')),

    CONSTRAINT ck_barbershop_payment_change_nonneg
        CHECK (change_minor IS NULL OR change_minor >= 0)
);

ALTER TABLE barbershop_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE barbershop_payment FORCE ROW LEVEL SECURITY;

CREATE POLICY barbershop_payment_tenant_isolation ON barbershop_payment
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Access paths: payments for a ticket (receipt), and the pending-tender sweep (company + status).
CREATE INDEX idx_barbershop_payment_ticket_id ON barbershop_payment (ticket_id);
CREATE INDEX idx_barbershop_payment_company_status ON barbershop_payment (company_id, status);

-- ---------------------------------------------------------------------------
-- user_outlet_assignment_ref (org-service assignment cache)
-- ---------------------------------------------------------------------------
-- A read model fed by the `UserOutletAssignmentChanged` event that org-service publishes via its
-- outbox. The consumer upserts idempotently: ASSIGNED -> active = true, UNASSIGNED -> active =
-- false, targeting the tenant-composite UNIQUE constraint (company_id, user_id, org_unit_id) so a
-- re-delivered event is a no-op (ON CONFLICT DO UPDATE of the same values). Column-for-column
-- identical to carwash-service's (and, before it, restaurant-service's) table of the same name.
--
-- Design notes:
--   * Rule 1 (database-per-service): org_unit_id and user_id are opaque references; no FK to
--     org-service, no cross-service joins, ever.
--   * Rule 2 (no sync calls): OutletAccessGuard (invoked by TicketWriter) reads exclusively from
--     this table — it never calls org-service to validate a cashier's outlet assignment
--     synchronously.
--   * Rule 4 (Auditable): the table carries all six Auditable columns; it is a derived read model so
--     it is NOT hash-chain-audited — Debezium CDC covers history.
--   * Rule 5 (RLS): ENABLE + FORCE ROW LEVEL SECURITY + tenant_isolation policy, identical predicate
--     to all other barbershop-service tables.
--   * Rule 8 (money): this table holds no monetary values; not applicable.
--   * Enforcement policy: cashier default-closed (must be in this table with active=true for the
--     requested org_unit_id); owner/manager bypass is applied in OutletAccessGuard before the guard
--     query, so this table is never queried for those roles.
--   * Ids only — no PII stored here.
--
-- PK = assignment_id, the canonical UUID carried on the event (the org-service user_outlet_assignment
-- row id). A cross-tenant assignment_id collision — astronomically unlikely with UUIDv4 — fails
-- closed on the PK unique violation and lands in the DLT rather than corrupting another tenant's
-- row; the composite UNIQUE constraint (company_id, user_id, org_unit_id) is the upsert target.
CREATE TABLE user_outlet_assignment_ref (

    -- The org-service assignment row id, carried verbatim from the event.
    -- Primary key: one row per assignment lifecycle (org-service owns this UUID).
    assignment_id   UUID         NOT NULL PRIMARY KEY,

    -- Keycloak subject (sub claim) of the assigned user.  Opaque string reference;
    -- no FK (database-per-service rule 1).
    user_id         VARCHAR(64)  NOT NULL,

    -- The org-unit (outlet) UUID from org-service.  Opaque reference; no FK.
    org_unit_id     UUID         NOT NULL,

    -- Post-change state: true = ASSIGNED, false = UNASSIGNED.  The consumer always
    -- upserts the current state so repeated delivery of the same event is idempotent.
    active          BOOLEAN      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    -- Tenant-composite upsert key.  The consumer targets ON CONFLICT ON CONSTRAINT
    -- uq_user_outlet_assignment_ref_scope rather than the bare PK so that a
    -- (negligible-probability) assignment_id collision across two tenants fails closed
    -- on the PK instead of UPDATE-ing another tenant's row.  RLS further enforces
    -- that any UPDATE can only touch the session tenant's rows (belt + suspenders).
    CONSTRAINT uq_user_outlet_assignment_ref_scope
        UNIQUE (company_id, user_id, org_unit_id)
);

ALTER TABLE user_outlet_assignment_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_outlet_assignment_ref FORCE ROW LEVEL SECURITY;

-- Tenant isolation: every SELECT/INSERT/UPDATE/DELETE is filtered to the session
-- tenant.  current_setting('app.current_tenant', true) returns NULL when no GUC is
-- set, so the predicate is false for every row — fail closed.
CREATE POLICY user_outlet_assignment_ref_tenant_isolation
    ON user_outlet_assignment_ref
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot guard-lookup index: OutletAccessGuard evaluates
--   EXISTS (SELECT 1 FROM user_outlet_assignment_ref
--            WHERE company_id = ? AND user_id = ? AND org_unit_id = ? AND active = true)
-- This partial index covers (company_id, user_id) and filters to active=true rows only,
-- keeping the index small as UNASSIGNED (active=false) rows are cold data.
CREATE INDEX idx_user_outlet_assignment_ref_active
    ON user_outlet_assignment_ref (company_id, user_id)
    WHERE active = TRUE;
