-- finance-service V45 — Platform SETTLEMENT: recording that a channel's platform payout has landed
-- (ADR 0036 "Register sessions + platform channel settlements", Phase C). Pairs with V44's
-- `platform_receivable` accumulator and the PLATFORM_RECEIVABLE / PLATFORM_FEE_EXPENSE role map it
-- seeded — this migration adds ONLY the settlement history table; no new chart-of-account or
-- role_account_map rows (V44 already carries every account this feature posts to; CASH_CLEARING
-- (1900, V13) is reused as-is for the net-cash leg).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — the accounts this table's postings debit/credit (1250, 5710,
-- 1900) are the SAME illustrative placeholders V13/V44 seeded; nothing new to gate here. See V44's
-- header for the replace-via-higher-version-data recipe.
-- ============================================================================================================================
--
-- Scenario (dashboard user, repeatable per channel): "GoFood settled gross X, we received net Y" —
-- the platform's commission (fee = gross − net) is recognised as an expense at the SAME moment. The
-- (future) PlatformSettlementWriter (PayrollSettlementWriter idiom, ADR 0036 Phase C) does, in one
-- @Transactional unit of work:
--   1. Replay-by-Idempotency-Key probe FIRST (AssetDisposalWriter/PayrollSettlementWriter idiom) —
--      same-key replay returns the original settlement, never posts twice.
--   2. pg_advisory_xact_lock(hashtext('platform_settlement:' || company_id || ':' || channel_code))
--      — serialises concurrent settlements of the SAME channel within the tenant (the InvoiceWriter /
--      within-company-close precedent), so two racing settlements against the same
--      platform_receivable row do not both pass a stale outstanding read.
--   3. A GUARDED UPDATE against V44's accumulator:
--        UPDATE platform_receivable
--           SET outstanding_minor = outstanding_minor - :gross, ...
--         WHERE company_id = :company_id AND channel_code = :channel_code AND currency = :currency
--           AND outstanding_minor >= :gross
--      Zero rows updated = an attempt to settle more than the channel is outstanding for (an
--      over-settle) → reject 422. The advisory lock (step 2) plus this WHERE guard together close the
--      TOCTOU window a plain read-then-write would leave open between concurrent settlement requests.
--   4. Posts Dr CASH_CLEARING (net) + Dr PLATFORM_FEE_EXPENSE (fee — the leg is OMITTED when
--      fee = 0, a full-net settlement with no commission) / Cr PLATFORM_RECEIVABLE (gross) — balanced,
--      exactly the shape V44's header documents.
--   5. Inserts ONE `platform_settlement` row (this table) — the settlement history AND the record a
--      support/audit trail can point to for "why did outstanding_minor move".
--
-- Money (rule 8): every *_minor column is an integer in minor units; `currency` is the single
-- ISO-4217 code shared by gross/net/fee (all three legs of one settlement transact in the same
-- currency — no FX inside a single settlement). Never a float.
--
-- Why `fee_minor` is a STORED column, not purely derived at read time: it is DERIVABLE
-- (gross_minor − net_minor) but persisted anyway for the immutable audit trail — once written, a
-- settlement row must stand on its own for history/reporting without depending on the accumulator or
-- GL still agreeing at read time. `ck_platform_settlement_fee` keeps the stored value honest — the
-- row can never assert a fee that does not reconcile to its own gross/net, so persisting it costs
-- nothing in correctness while giving reporting a single indexed column instead of a subtraction.
--
-- `journal_entry_id` is a plain UUID, NOT a foreign key — mirrors `payroll_settlement.journal_entry_id`
-- (V41) and `bill.journal_entry_id` / `invoice.journal_entry_id` elsewhere in this schema (V39's
-- comment explains the pattern): the settlement entry is always built and persisted in the SAME
-- transaction as this row (never recorded before its entry exists here), so the missing FK trades
-- away a constraint this writer's own ordering already guarantees, for consistency with the rest of
-- the settlement-table family.
--
-- `channel_code` is a snapshot string, NOT a foreign key — channel codes are owned by
-- restaurant-service (ADR 0036 Phase A); finance only ever sees them as strings riding
-- SaleRecorded/SaleVoided/settlement-request payloads (rule: never a cross-service join, CLAUDE.md
-- hard rule 1). Same treatment as V44's `platform_receivable.channel_code`.
-- ============================================================================================================================

CREATE TABLE platform_settlement (
    id                  UUID         NOT NULL PRIMARY KEY,
    channel_code        VARCHAR(32)  NOT NULL,

    -- Money (rule 8): integer minor units + ISO-4217 code. NEVER a float. All three amounts below
    -- share the single `currency` column — one settlement transacts in one currency.
    gross_minor         BIGINT       NOT NULL,
    net_minor           BIGINT       NOT NULL,
    fee_minor           BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,

    -- The posted settlement entry (Dr CASH_CLEARING + Dr PLATFORM_FEE_EXPENSE / Cr
    -- PLATFORM_RECEIVABLE) — plain UUID, no FK; see the header note above.
    journal_entry_id    UUID         NOT NULL,
    settled_at          TIMESTAMPTZ  NOT NULL,

    -- The Idempotency-Key that produced this settlement — the replay lookup key, mirroring
    -- payroll_settlement.idempotency_key (V41): keyless -> 400, same-key replay -> 200 (this row),
    -- a DIFFERENT key against an already-consumed identity -> 409.
    idempotency_key     VARCHAR(64)  NOT NULL,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at          TIMESTAMPTZ  NOT NULL,
    created_by          VARCHAR(255) NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    updated_by          VARCHAR(255) NOT NULL,
    version             BIGINT       NOT NULL,
    company_id          VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_platform_settlement_gross_positive CHECK (gross_minor > 0),
    CONSTRAINT ck_platform_settlement_net_nonnegative CHECK (net_minor >= 0),
    -- The fee-honesty guard: fee_minor MUST equal gross_minor - net_minor (rejects a mismatched
    -- stored fee) AND must be non-negative (rejects net > gross — a platform "subsidy" settlement is
    -- out of scope per V44's SME-confirmation note C; PlatformSettlementWriter never builds that
    -- shape, and this CHECK is the database backstop that makes it impossible to persist anyway).
    CONSTRAINT ck_platform_settlement_fee
        CHECK (fee_minor = gross_minor - net_minor AND fee_minor >= 0),

    CONSTRAINT uq_platform_settlement_idem UNIQUE (company_id, idempotency_key)
);

ALTER TABLE platform_settlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_settlement FORCE ROW LEVEL SECURITY;

CREATE POLICY platform_settlement_tenant_isolation ON platform_settlement
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The channel settlement-history read (most-recent-first per channel) — the hot path for a dashboard
-- "settlement history" panel. Composite on (company_id, channel_code, settled_at DESC) so a tenant's
-- per-channel history is served by a single index scan with zero sort step, mirroring
-- idx_journal_entry_company_period's leading-company_id convention.
CREATE INDEX idx_platform_settlement_history
    ON platform_settlement (company_id, channel_code, settled_at DESC);
