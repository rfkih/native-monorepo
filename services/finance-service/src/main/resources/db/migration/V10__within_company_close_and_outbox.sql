-- finance-service V10 (P3d SEAM 4a — THE PRODUCERS) — the transactional outbox + the within-company
-- close aggregate.
--
-- ============================================================================================
-- WHAT THIS SEAM CLOSES: the event loop end-to-end. Until now finance was a PURELY DOWNSTREAM
-- consumer (V1 baseline: "it does not publish, so it declares NO OutboxWriter"). SEAM 4a makes
-- finance EMIT for the first time:
--   * ConsolidationClosed — on a within-company close (group_id=NULL) and on a GROUP close
--     (group_id=G, only when the summary reaches CLOSED). notification-service already consumes it.
--   * TrialBalancePublished — on a within-company close, one event per group the company belongs to,
--     so the SEAM-2 group ingest finally has a real producer feeding it.
-- Both publish through the transactional outbox (rule 3), atomic with the close that produced them.
-- ============================================================================================

-- ---------------------------------------------------------------------------
-- outbox (the transactional outbox the relay / Debezium tails)
-- ---------------------------------------------------------------------------
-- Columns match libs/events OutboxRecord / OutboxWriter EXACTLY (the same DDL org-service /
-- restaurant-service use). published_at is set by the relay (ignored by Debezium). NOT Auditable and
-- NOT under RLS: it is relay infrastructure, and its rows already carry company_id for downstream
-- tenant scoping; the writer always runs inside the close's own transaction, which is itself RLS-
-- scoped, so the outbox row commits atomically with the close (rule 3 — if the close rolls back the
-- event is never emitted; if it commits, the relay sees exactly one row).
CREATE TABLE outbox (
    id             UUID                     NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(255)             NOT NULL,
    aggregate_id   VARCHAR(255)             NOT NULL,
    event_type     VARCHAR(255)             NOT NULL,
    payload        BYTEA                    NOT NULL,
    headers        TEXT                     NULL,
    company_id     UUID                     NOT NULL,
    occurred_at    TIMESTAMPTZ              NOT NULL,
    published_at   TIMESTAMPTZ              NULL
);

-- The relay polls for UNPUBLISHED rows in occurrence order. A PARTIAL index (WHERE published_at IS
-- NULL) indexes only the rows the relay still has to ship, so it stays tiny.
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- within_company_close (the within-company close aggregate) — Auditable + RLS
-- ---------------------------------------------------------------------------
-- One row per (company_id, period) that the company has CLOSED. It is the within-company analogue of
-- consolidation_summary (which is the GROUP close): it records that the company finalised its own
-- period and emitted ConsolidationClosed(group_id=null) + a TrialBalancePublished per group it belongs
-- to. The UNIQUE (company_id, period) is the IDEMPOTENCY guard: a second close of an already-closed
-- (company, period) is a clean no-op that re-emits NOTHING (a held re-close cannot double-fire the
-- loop-closing events). reconciled / uses_illustrative_rules are the published trial-balance flags,
-- captured for audit + so the dashboard can badge the close.
--
-- Auditable + FORCE RLS scoped to company_id (the closing company is the tenant) — the existing
-- finance tenant-table precedent (ledger_posting / consolidated_pnl).
CREATE TABLE within_company_close (
    id                      UUID         NOT NULL PRIMARY KEY,

    -- The accounting period the company closed (YYYY-MM).
    period                  VARCHAR(7)   NOT NULL,

    -- The company's base (functional) currency at close (ISO-4217). The trial balance is expressed in
    -- it, and it is carried on the emitted TrialBalancePublished.base_currency.
    base_currency           CHAR(3)      NOT NULL,

    -- Whether the company's trial balance reconciled (it always does by construction — the close adds
    -- a balancing retained-earnings EQUITY line so the signed double-entry residual is zero), captured
    -- so the emitted event's reconciled flag is auditable.
    reconciled              BOOLEAN      NOT NULL DEFAULT true,

    -- Sticky-OR from any illustrative-derived posting (e.g. placeholder payroll labor cost) in the
    -- period's ledger. Carried onto every emitted event so a placeholder-derived close is loud.
    uses_illustrative_rules BOOLEAN      NOT NULL DEFAULT false,

    -- Auditable (libs/tenant) — present on every Native table. company_id is the CLOSING company.
    created_at              TIMESTAMPTZ  NOT NULL,
    created_by              VARCHAR(255) NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    updated_by              VARCHAR(255) NOT NULL,
    version                 BIGINT       NOT NULL,
    company_id              VARCHAR(64)  NOT NULL,

    -- IDEMPOTENCY: at most one close per (company, period). A re-close finds the row and no-ops,
    -- re-emitting nothing.
    CONSTRAINT uq_within_company_close UNIQUE (company_id, period)
);

ALTER TABLE within_company_close ENABLE ROW LEVEL SECURITY;
ALTER TABLE within_company_close FORCE ROW LEVEL SECURITY;

CREATE POLICY within_company_close_tenant_isolation ON within_company_close
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- member_group_index (member -> the groups it belongs to) — NOT Auditable, NOT under RLS
-- ---------------------------------------------------------------------------
-- The REVERSE index of group_member: given a MEMBER company, which group(s) does it belong to? A
-- within-company close runs as the CLOSING company (the member) — NOT as any group's lead — so it
-- cannot read the LEAD-scoped group_member read model under RLS (the lead owns those rows). To emit a
-- TrialBalancePublished per group the company belongs to, the close needs a member-queryable lookup.
--
-- This is cross-tenant reference infrastructure (exactly like group_lead): keyed by member_company_id,
-- it mirrors the effective-dated membership window so the close can resolve the groups ACTIVE at
-- period-end. It is written alongside the LEAD-scoped group_member row by the membership consumer (the
-- SAME GroupMembershipChanged that maintains group_member maintains this), so it is consistent with the
-- authoritative member set without a sync call (rule 2). It holds only the public member->group
-- structural mapping + effective window (no amount, no PII).
CREATE TABLE member_group_index (
    member_company_id UUID NOT NULL,
    group_id          UUID NOT NULL,

    -- The membership effective window, mirrored from the event (open = the 9999-12-31 sentinel, never
    -- NULL), so the close resolves the groups ACTIVE at its period-end deterministically.
    effective_from    DATE NOT NULL,
    effective_to      DATE NOT NULL DEFAULT DATE '9999-12-31',

    -- One row per (member, group): the membership consumer upserts on it (ADDED opens/re-opens the
    -- window, REMOVED closes it), mirroring group_member.
    CONSTRAINT uq_member_group_index UNIQUE (member_company_id, group_id)
);

-- The close scans by member to find its active groups; index that access path.
CREATE INDEX idx_member_group_index_member ON member_group_index (member_company_id, effective_to);
