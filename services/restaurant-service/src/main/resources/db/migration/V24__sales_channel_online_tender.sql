-- restaurant-service V24 — online sales channels + ONLINE tender (ADR 0036 Phase B2).
--
-- The POS rings an online-platform order (GoFood/GrabFood-style) with tender ONLINE and a channel
-- picked at payment; the platform collects from the customer and pays the merchant later minus a
-- commission (ADR 0036 decision #4). This migration adds the company-managed channel catalog and
-- the two additive columns that let `sale`/`payment` carry which channel a tender rang through, so
-- SaleRecorded/SaleVoided/SaleRefunded can thread `channel_code` to finance (the ONLINE clearing
-- leg routes to PLATFORM_RECEIVABLE, per ADR 0036 decision #6 — deployed there before any producer
-- may emit an ONLINE sale).
--
-- Money throughout is minor units (BIGINT) + an ISO-4217 `currency` column — never a float (rule 8).
-- Every new table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a <table>_tenant_isolation
-- policy (USING + WITH CHECK) keyed to app.current_tenant, exactly as V1/V3/V21/V22.

-- ---------------------------------------------------------------------------
-- 1. sales_channel — company-managed CRUD catalog of online platforms
-- ---------------------------------------------------------------------------
-- `code` (e.g. GOFOOD, GRABFOOD) is IMMUTABLE once created: app-enforced only (the write path never
-- exposes an update-code operation), no trigger — mirrors how V21/V22 push several invariants to the
-- writer instead of a constraint, to avoid constraint churn as the feature evolves. The app normalizes
-- to uppercase before insert; no CHECK enforces case here, same rationale as V21's tender_type (no
-- CHECK on values, writer-enforced enum) so a future channel-code shape doesn't need a migration.
-- `active` is a soft-deactivate flag: a channel is never hard-deleted while `sale`/`payment` rows
-- still snapshot its code (see parts 2-3 below) — deactivation only stops it from being offered as a
-- NEW selection at payment; historical rows keep their snapshot code regardless.
CREATE TABLE sales_channel (
    id              UUID         NOT NULL PRIMARY KEY,

    -- Immutable identity, e.g. GOFOOD, GRABFOOD. Uppercase by app convention (no CHECK — see header).
    code            VARCHAR(32)  NOT NULL,

    -- Display name; editable (unlike code).
    name            VARCHAR(120) NOT NULL,

    -- Soft-deactivate; never hard-delete while referenced (see header).
    active          BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_sales_channel_code UNIQUE (company_id, code)
);

ALTER TABLE sales_channel ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales_channel FORCE ROW LEVEL SECURITY;

CREATE POLICY sales_channel_tenant_isolation ON sales_channel
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- 2. sale — channel_code, additive/nullable snapshot (no backfill; see V21/V22 rationale)
-- ---------------------------------------------------------------------------
-- Set only for ONLINE-tender sales (app-enforced; no CHECK here — mirrors V21's tender_type and
-- V22's cash_collected_minor rationale: the writer enforces the pairing with tender_type, which
-- avoids a constraint drop/recreate as the feature evolves). Deliberately NOT a foreign key to
-- sales_channel: this is a SNAPSHOT of the code at sale time, which must survive the channel later
-- being deactivated (or, in principle, renamed) without retroactively invalidating historical sales.
-- `sale` is FORCE-RLS, so — exactly as V21/V22 document — a Flyway UPDATE backfill here would run
-- unbound (no app.current_tenant GUC in a migration session) and silently match zero rows; there is
-- nothing to backfill anyway, since only sales recorded after this column exists can have a channel.
ALTER TABLE sale
    ADD COLUMN channel_code VARCHAR(32) NULL;

-- ---------------------------------------------------------------------------
-- 3. payment — channel_code, additive/nullable snapshot (same semantics as sale, part 2)
-- ---------------------------------------------------------------------------
ALTER TABLE payment
    ADD COLUMN channel_code VARCHAR(32) NULL;

-- Re-add ck_payment_tender_type (V3) to admit ONLINE. All existing rows already satisfy
-- tender_type <> 'ONLINE' (the value did not exist before this migration), so the re-add validates
-- trivially with no table rewrite concern.
ALTER TABLE payment
    DROP CONSTRAINT IF EXISTS ck_payment_tender_type;

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_tender_type CHECK (tender_type IN ('CASH', 'QRIS', 'CARD', 'ONLINE'));

-- An ONLINE payment must carry the channel it rang through; every other tender is unconstrained here
-- (a CASH/QRIS/CARD payment may or may not carry a channel_code — v1 never sets one for those).
ALTER TABLE payment
    ADD CONSTRAINT ck_payment_online_requires_channel
        CHECK (tender_type <> 'ONLINE' OR channel_code IS NOT NULL);
