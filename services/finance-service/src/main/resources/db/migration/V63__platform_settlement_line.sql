-- ============================================================================================================================
-- V63 — one payout, many sources (ADR 0076 phase 2)
-- ============================================================================================================================
-- A merchant on Shopee's QRIS receives ONE bank transfer covering both its ShopeeFood orders (GL
-- 1250) and its counter QRIS sales (GL 1901). ADR 0036's settlement could only clear one channel
-- against one account, so that single transfer had to be entered twice, through two screens.
--
-- A settlement becomes a HEADER (the payout: its payer, the date, and the NET the merchant actually
-- received — the one figure they can read straight off the bank statement) plus one LINE per source
-- cleared, each carrying its own gross. `fee = Σ gross − net` still holds; each line's share of it
-- is allocated pro-rata and booked to that line's OWN fee account, so "QRIS fee" (5720) keeps
-- meaning something instead of being absorbed into "marketplace fee" (5710).
--
-- The header is unchanged and keeps its meaning: `platform_settlement.channel_code` now holds the
-- PAYER, which for every historical row it already was (a single-channel payout is paid by that
-- channel). No column is added, renamed or dropped there — the previous image keeps reading and
-- writing it exactly as before.
--
-- CARD lines are deliberately NOT settleable yet: there is no CARD_FEE_EXPENSE role in the chart,
-- and routing a card acquirer's fee to PLATFORM_FEE_EXPENSE would misstate the P&L. Card balances
-- accrue and are visible; they settle through bank reconciliation as they do today until an SME
-- agrees a fee account.
-- ============================================================================================================================

CREATE TABLE platform_settlement_line (
    id             UUID         NOT NULL PRIMARY KEY,

    -- The payout this line belongs to. ON DELETE is deliberately absent: settlements are
    -- append-only (ADR 0036), so a line is never orphaned by a delete that cannot happen.
    settlement_id  UUID         NOT NULL REFERENCES platform_settlement (id),

    -- Which sub-ledger row this line cleared. The pair (source_kind, channel_code) is exactly the
    -- identity `platform_receivable` is keyed by, so the guarded decrement can find it.
    source_kind    VARCHAR(16)  NOT NULL,
    channel_code   VARCHAR(32)  NOT NULL,

    -- Money (rule 8): integer minor units + ISO-4217. NEVER a float.
    -- gross_minor — what this source settled. fee_minor — its pro-rata share of the payout's
    -- deduction, booked to the fee account for its kind. The two sum across lines to the header's
    -- gross and fee respectively; the remainder from pro-rata rounding lands on the largest line so
    -- the journal stays exact to the rupiah.
    gross_minor    BIGINT       NOT NULL,
    fee_minor      BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_platform_settlement_line_kind
        CHECK (source_kind IN ('MARKETPLACE', 'QRIS', 'CARD')),
    -- A line must settle something, and a fee is never negative (that would be a subsidy — still
    -- out of scope, as in ADR 0036 §5, where net > gross is a 422).
    CONSTRAINT ck_platform_settlement_line_gross_positive CHECK (gross_minor > 0),
    CONSTRAINT ck_platform_settlement_line_fee_nonnegative CHECK (fee_minor >= 0),
    -- One line per source per payout: two lines for the same source would make the guarded
    -- decrement's arithmetic ambiguous and let a replay compare unequal sets.
    CONSTRAINT uq_platform_settlement_line_source
        UNIQUE (settlement_id, source_kind, channel_code)
);

ALTER TABLE platform_settlement_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_settlement_line FORCE ROW LEVEL SECURITY;

CREATE POLICY platform_settlement_line_tenant_isolation ON platform_settlement_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The history read walks lines by their payout.
CREATE INDEX idx_platform_settlement_line_settlement
    ON platform_settlement_line (company_id, settlement_id);

-- ---------------------------------------------------------------------------
-- Backfill: every historical payout becomes a one-line payout.
-- ---------------------------------------------------------------------------
-- Without this the history screen would show older settlements as having cleared nothing. Each
-- existing row was a single MARKETPLACE channel settling its whole gross, which is precisely one
-- line carrying the header's own figures.
--
-- Both tables are FORCE ROW LEVEL SECURITY and Flyway runs as the table owner with no
-- `app.current_tenant` GUC set — the INSERT…SELECT would read ZERO source rows and the WITH CHECK
-- would reject what little it wrote (the fleet's known migration-backfill gotcha, cf.
-- payment-service V6). Drop FORCE on both for the backfill, then restore immediately.
ALTER TABLE platform_settlement      NO FORCE ROW LEVEL SECURITY;
ALTER TABLE platform_settlement_line NO FORCE ROW LEVEL SECURITY;

INSERT INTO platform_settlement_line
    (id, settlement_id, source_kind, channel_code, gross_minor, fee_minor, currency,
     created_at, created_by, updated_at, updated_by, version, company_id)
SELECT gen_random_uuid(),
       s.id,
       'MARKETPLACE',
       s.channel_code,
       s.gross_minor,
       s.fee_minor,
       s.currency,
       s.created_at,
       s.created_by,
       s.updated_at,
       s.updated_by,
       0,
       s.company_id
  FROM platform_settlement s;

ALTER TABLE platform_settlement_line FORCE ROW LEVEL SECURITY;
ALTER TABLE platform_settlement      FORCE ROW LEVEL SECURITY;
