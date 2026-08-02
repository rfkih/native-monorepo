-- finance-service V44 — Platform channel receivable: GL wiring + per-channel sub-ledger (ADR 0036
-- "Register sessions + platform channel settlements", Phase B). DEPLOY-FINANCE-FIRST: this migration
-- (and the RevenuePostingWriter / ReversalPostingWriter code that reads the new role map) must be
-- live BEFORE any vertical producer is allowed to emit an ONLINE-tender SaleRecorded — otherwise
-- platform money mis-books as drawer cash (ADR 0036 §"Deploy-order rule").
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as
-- V13/V25/V28/V30/V32/V43).
-- ============================================================================================================================
-- Online-delivery platforms (GoFood/GrabFood-style) collect from the customer and pay the merchant
-- later minus a commission. PSAK 72 treats the merchant as principal, so revenue/tax legs post
-- exactly as any other sale; only the CLEARING leg differs — an ONLINE-tender sale never touches
-- drawer cash, so it must not route to CASH_CLEARING (1900). This migration adds:
--   1. Two illustrative chart-of-account rows: 1250 (Platform Receivable, ASSET) and 5710
--      (Platform Fee Expense, EXPENSE).
--   2. Two role_account_map rows: PLATFORM_RECEIVABLE -> 1250, PLATFORM_FEE_EXPENSE -> 5710
--      (version 1, illustrative) — the AccountRole values RevenuePostingWriter's
--      resolveClearingRole and the (future) PlatformSettlementWriter resolve against.
--   3. `platform_receivable` — a per-channel receivable ACCUMULATOR (company_id, channel_code,
--      currency), the same upsert-accumulator shape as consolidated_revenue (V1) / outlet_revenue
--      (V21): a sub-ledger of GL account 1250 giving per-channel granularity the GL itself does not
--      carry (1250 is one shared account across every channel). Written via the org_unit_ref /
--      RevenuePostingWriter upsert idiom (JdbcTemplate `INSERT ... ON CONFLICT (company_id,
--      channel_code, currency) DO UPDATE ...`), never a read-modify-write — from
--      RevenuePostingWriter (increment on an ONLINE sale) and ReversalPostingWriter (decrement on a
--      refund/void of an ONLINE sale). A later PlatformSettlementWriter debits it when a payout is
--      recorded (Dr CASH_CLEARING(net) + Dr PLATFORM_FEE_EXPENSE(fee) / Cr PLATFORM_RECEIVABLE(gross)).
--
-- 1250 / 5710 verified collision-free: grepping every `INSERT INTO chart_of_account` across V1-V43
-- (the same check V36/V39/V43 performed) shows the seeded 12xx code space holds only 1200 (AR, V25)
-- — 1250 is free between it and 1300 (VAT_INPUT, V28) — and the seeded 57xx code space holds only
-- 5700 (Cash Short Expense, V43) — 5710 is free between it and 6000 (Labor, V3). Neither 1250 nor
-- 5710 collides with any existing account_code.
--
-- Money (rule 8): `outstanding_minor` is an integer in minor units; `currency` is the ISO-4217 code
-- carried alongside it. Never a float.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 1250 — Platform Receivable (ASSET). Debited when an ONLINE-tender sale posts (the platform owes
--         the merchant the gross amount); credited when the platform's payout is settled.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1250', 'Platform Receivable (ILLUSTRATIVE — SME-gated)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- 5710 — Platform Fee Expense (EXPENSE). Debited for the platform's commission at settlement time
--         (fee = gross settled − net received; the whole commission books to expense in v1 — no
--         input-VAT split, ADR 0036 "Quantified v1 approximations").
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('5710', 'Platform Fee Expense (ILLUSTRATIVE — SME-gated)', 'EXPENSE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- role_account_map: PLATFORM_RECEIVABLE → 1250, PLATFORM_FEE_EXPENSE → 5710 (version 1,
-- illustrative). CASH_CLEARING → 1900 (V13) is reused as the net-cash leg of a platform settlement.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'PLATFORM_RECEIVABLE',   '1250', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'PLATFORM_FEE_EXPENSE',  '5710', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- platform_receivable — per-channel receivable accumulator (Auditable + FORCE RLS).
-- ---------------------------------------------------------------------------
-- One row per (company_id, channel_code, currency); outstanding_minor accumulates as ONLINE sales
-- (increment) and their refunds/settlements (decrement) land. channel_code 'UNKNOWN' is a VALID
-- value — the routing default when an ONLINE sale arrives with no channel attached. Deliberately NO
-- non-negative CHECK: a settlement can clear the balance to zero and a subsequent refund clawback on
-- an already-fully-settled channel is expected to drive outstanding_minor negative — that is
-- tolerated by design, not an error state (ADR 0036 §4). The GL account (1250) stays authoritative
-- and company-wide; this table exists only to add the per-channel dimension the GL does not carry.
CREATE TABLE platform_receivable (
    id                  UUID         NOT NULL PRIMARY KEY,
    channel_code        VARCHAR(32)  NOT NULL,

    -- Money (rule 8): integer minor units + ISO-4217 code. NEVER a float.
    outstanding_minor   BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at          TIMESTAMPTZ  NOT NULL,
    created_by          VARCHAR(255) NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    updated_by          VARCHAR(255) NOT NULL,
    version             BIGINT       NOT NULL,
    company_id          VARCHAR(64)  NOT NULL,

    -- At most one accumulator per tenant + channel + currency. The writer upserts on this key
    -- (INSERT ... ON CONFLICT ... DO UPDATE), so a tenant's per-channel balance is a single row.
    CONSTRAINT uq_platform_receivable_channel UNIQUE (company_id, channel_code, currency)
);

ALTER TABLE platform_receivable ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_receivable FORCE ROW LEVEL SECURITY;

CREATE POLICY platform_receivable_tenant_isolation ON platform_receivable
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- No extra indexes: every read is by the (company_id, channel_code, currency) unique key above
-- (settlement lookup and the writer's upsert conflict target); a per-tenant listing, if ever added,
-- is served by the leading company_id column of that same unique index.

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. 1250 and 5710 are illustrative placeholders that must map to the client's real
--    receivable/commission-expense codes via a higher-version role_account_map row, no code change.
-- B. The whole platform commission books to PLATFORM_FEE_EXPENSE in v1 — not valid for a PKP
--    claiming input VAT on the commission; a VAT_INPUT split on the fee is additive later.
-- C. Platform subsidies (net settled > gross) are out of scope — PlatformSettlementWriter rejects
--    that shape (422); a subsidy-income treatment is deferred to the SME.
-- ============================================================================================================================
