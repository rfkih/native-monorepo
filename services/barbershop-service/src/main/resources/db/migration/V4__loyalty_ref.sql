-- barbershop-service V4 — Phase 4 of the POS-parity program (ADR 0027: loyalty-service and
-- eventual-consistency redemption). Adds the LOCAL loyalty/gift-card read models fed by
-- loyalty-service's outbox events, this vertical's own gift-card-SALE record (a liability event,
-- NOT revenue), and the nullable checkout columns that carry a redemption's selections through to
-- SaleRecorded emission. Identical DDL to restaurant-service V17 / carwash-service V9 — only this
-- header and the trailing ALTER's target table (barbershop_ticket) differ.
--
-- ---------------------------------------------------------------------------
-- The read-model self-healing contract (ADR 0027 decision 2)
-- ---------------------------------------------------------------------------
-- `member_balance_ref` and `gift_card_ref` below are LOCAL caches of loyalty-service's ledger of
-- record, fed by `LoyaltyBalanceChanged` / `GiftCardStateChanged` — events that carry the ABSOLUTE
-- current balance (never a delta) plus a monotonically increasing `balance_seq`. The consumer
-- upserts set-if-newer: `UPDATE ... SET points_balance = :v, balance_seq = :seq WHERE member_id = :id
-- AND balance_seq < :seq` (analogously for gift_card_ref), so out-of-order redelivery can never
-- regress a cache to a stale value — the cache self-heals to the latest authoritative snapshot
-- regardless of delivery order. Rule 2 (no sync calls) is upheld: this vertical never calls
-- loyalty-service to check a balance; it reads exclusively from these local tables.
--
-- ---------------------------------------------------------------------------
-- The atomic local decrement idiom (ADR 0027 decision 3) — the within-service double-spend guard
-- ---------------------------------------------------------------------------
-- At checkout, a redemption is clamped to the CACHED balance and atomically decremented in the same
-- statement that checks it:
--
--     UPDATE member_balance_ref
--        SET points_balance = points_balance - :redeemPoints
--      WHERE member_id = :memberId
--        AND points_balance >= :redeemPoints
--
-- (the identical idiom applies to gift_card_ref, decrementing balance_minor). Zero rows updated
-- means the clamp failed — the caller returns 409 Conflict. Because this is one atomic UPDATE, two
-- concurrent checkouts against the SAME cached row within THIS service instance cannot both succeed
-- past a balance that only covers one of them — double-spend within one vertical service is
-- impossible.
--
-- This does NOT bound a redemption race across services/outlets within replication lag: the
-- authoritative ledger (loyalty-service, consuming `SaleRecorded`) applies every redemption it sees
-- regardless of what the cache locally allowed, and may go NEGATIVE. loyalty-service — never this
-- service — detects that condition and emits `LoyaltyRedemptionFlagged` for manual follow-up; a
-- cross-service overdraft is FLAGGED, never silently lost. Consequently `points_balance` /
-- `balance_minor` below carry NO non-negative CHECK constraint: the next authoritative correction
-- may legitimately push the cached value negative, and the cache must be able to mirror it.
--
-- ---------------------------------------------------------------------------
-- General notes (mirrors this service's own user_outlet_assignment_ref precedent, V1 baseline)
-- ---------------------------------------------------------------------------
--   * Rule 1 (database-per-service): member_id / gift_card_id are opaque references minted by
--     loyalty-service (member) or minted AT THE TILL by this vertical (gift_card_id — ADR 0027
--     decision 5); no FK to loyalty-service, no cross-service joins, ever.
--   * Rule 4 (Auditable): every table carries all six Auditable columns; the read-model tables are
--     NOT hash-chain-audited (derived projections) — Debezium CDC covers history. `gift_card_sale`
--     IS a money-adjacent event source (see its own comment below).
--   * Rule 5 (RLS): ENABLE + FORCE ROW LEVEL SECURITY + a tenant_isolation policy (USING + WITH
--     CHECK), identical predicate to every other barbershop-service table.
--   * Rule 6 (PII): NO PII COLUMNS anywhere in this migration — ids, states, and numbers only.
--     Member phone/name PII lives ONLY in loyalty-service (ADR 0027 decision 1); it is never on the
--     wire in any event this service consumes, and never stored here.
--   * Rule 8 (money): every monetary column is BIGINT minor units + a CHAR(3) ISO-4217 currency
--     column. Never a float.

-- ---------------------------------------------------------------------------
-- member_balance_ref — LOCAL cache of loyalty-service's member points balance
-- ---------------------------------------------------------------------------
-- One row per member ever seen by this outlet's checkout. member_id IS the functional identity (an
-- absolute-value cache, not a lifecycle log), so — mirroring user_outlet_assignment_ref's house
-- style of "PK = the canonical upstream-owned id, plus a defensive tenant-composite UNIQUE as the
-- upsert target" — the bare member_id is the PK and (company_id, member_id) is the belt-and-
-- suspenders composite the consumer's ON CONFLICT targets, so a (negligible-probability) member_id
-- collision across two tenants fails closed on the PK rather than UPDATE-ing another tenant's row.
CREATE TABLE member_balance_ref (

    -- The loyalty-service member id, carried verbatim on LoyaltyBalanceChanged. Primary key: one row
    -- per member (loyalty-service owns this UUID).
    member_id       UUID         NOT NULL PRIMARY KEY,

    -- Absolute current points balance (NOT a delta) — set-if-newer by balance_seq. MAY GO NEGATIVE:
    -- see the atomic-decrement / cross-service-race comment above. No non-negative CHECK — deliberate.
    points_balance  BIGINT       NOT NULL,

    -- Monotonic per-member sequence carried on LoyaltyBalanceChanged; the set-if-newer upsert guard
    -- (WHERE balance_seq < :seq) makes redelivery / out-of-order delivery a no-op or a correction,
    -- never a regression.
    balance_seq     BIGINT       NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_member_balance_ref_company_member UNIQUE (company_id, member_id)
);

ALTER TABLE member_balance_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_balance_ref FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename  = 'member_balance_ref'
           AND policyname = 'member_balance_ref_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY member_balance_ref_tenant_isolation
                ON member_balance_ref
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- gift_card_ref — LOCAL cache of loyalty-service's gift-card state, same set-if-newer contract
-- ---------------------------------------------------------------------------
CREATE TABLE gift_card_ref (

    -- The loyalty-service gift_card id — which is itself the id minted AT THE TILL when the card was
    -- sold (ADR 0027 decision 5), carried verbatim on GiftCardStateChanged. Primary key, mirroring
    -- member_balance_ref's house style.
    gift_card_id    UUID         NOT NULL PRIMARY KEY,

    state           VARCHAR(12)  NOT NULL,

    -- Absolute current stored-value balance (NOT a delta) — set-if-newer by balance_seq. MAY GO
    -- NEGATIVE: same cross-service-race / flag policy as member_balance_ref.points_balance above. No
    -- non-negative CHECK — deliberate.
    balance_minor   BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,

    -- Monotonic per-card sequence carried on GiftCardStateChanged; same set-if-newer upsert guard.
    balance_seq     BIGINT       NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_gift_card_ref_state
        CHECK (state IN ('ACTIVE', 'DEPLETED', 'EXPIRED', 'VOID')),
    CONSTRAINT uq_gift_card_ref_company_card UNIQUE (company_id, gift_card_id)
);

ALTER TABLE gift_card_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE gift_card_ref FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename  = 'gift_card_ref'
           AND policyname = 'gift_card_ref_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY gift_card_ref_tenant_isolation
                ON gift_card_ref
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- gift_card_sale — THIS vertical's own record of selling a gift card at the till
-- ---------------------------------------------------------------------------
-- Emits `GiftCardSold` via the outbox in the same transaction (rule 3). This is deliberately NOT
-- folded into `sale` / SaleRecorded: selling a gift card is NOT revenue — it is a liability event
-- (ADR 0027 decision 4: `Dr <tender clearing> / Cr GIFT_CARD_LIABILITY` in finance, never touching
-- the revenue legs). gift_card_id carries the UUID minted at the till at the moment of sale (ADR
-- 0027 decision 5) — the SAME id that becomes both loyalty-service's `gift_card.id` and, once
-- GiftCardStateChanged replicates back, this table's own `gift_card_ref.gift_card_id`.
--
-- No FK to gift_card_ref: this row is written synchronously at sale time, before the card is
-- necessarily redeemable anywhere (GiftCardStateChanged has not replicated back yet — a card becomes
-- redeemable only once it does), so gift_card_ref may not (yet) hold this gift_card_id when this row
-- is inserted. Same "checkout-time snapshot, not a join target" reasoning as e.g. this service's own
-- barbershop_ticket_line.item_id.
CREATE TABLE gift_card_sale (
    id                UUID         NOT NULL PRIMARY KEY,

    -- The card sold — minted at the till, not generated by this table (see comment above).
    gift_card_id      UUID         NOT NULL,

    -- The outlet (org_unit) this card was sold at.
    business_id       UUID         NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float (rule 8). The value
    -- loaded onto the card at sale — always positive (a gift-card sale cannot load zero or negative
    -- value).
    amount_minor      BIGINT       NOT NULL,
    currency          CHAR(3)      NOT NULL,

    -- CASH | QRIS | CARD, mirroring this vertical's payment tender types; nullable for a legacy /
    -- unknown-tender path.
    tender_type       VARCHAR(16)  NULL,

    occurred_at       TIMESTAMPTZ  NOT NULL,

    -- Producer idempotency: exactly one gift_card_sale row (and one GiftCardSold event) per
    -- (company, client request id).
    idempotency_key   TEXT         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_gift_card_sale_amount_minor_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_gift_card_sale_tender_type
        CHECK (tender_type IS NULL OR tender_type IN ('CASH', 'QRIS', 'CARD')),
    CONSTRAINT uq_gift_card_sale_company_idempotency UNIQUE (company_id, idempotency_key)
);

ALTER TABLE gift_card_sale ENABLE ROW LEVEL SECURITY;
ALTER TABLE gift_card_sale FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename  = 'gift_card_sale'
           AND policyname = 'gift_card_sale_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY gift_card_sale_tenant_isolation
                ON gift_card_sale
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

-- Access path: support/audit lookup of the sale record for a given card.
CREATE INDEX idx_gift_card_sale_card ON gift_card_sale (company_id, gift_card_id);

-- ---------------------------------------------------------------------------
-- barbershop_ticket — nullable, additive checkout columns for a loyalty/gift-card redemption
-- ---------------------------------------------------------------------------
-- Selected at checkout. All five are nullable: most tickets redeem nothing.
ALTER TABLE barbershop_ticket
    ADD COLUMN loyalty_member_id           UUID   NULL,
    ADD COLUMN loyalty_redeemed_points     BIGINT NULL,
    ADD COLUMN loyalty_redeemed_minor      BIGINT NULL,
    ADD COLUMN gift_card_id                UUID   NULL,
    ADD COLUMN gift_card_redeemed_minor    BIGINT NULL;
