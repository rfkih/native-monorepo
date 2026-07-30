-- loyalty-service baseline — Phase 4 of the POS-parity program (ADR 0027): the sole owner of the
-- loyalty points / gift-card ledger of record, and the ONLY home of member PII (phone, display
-- name — column-level encrypted). One baseline folds in everything the service needs on day one
-- (outbox WITH traceparent from the start — the carwash V1+V3 / barbershop V1 lesson):
--
--   * outbox / processed_event  — the transactional-outbox + idempotent-consumer dedupe machinery.
--   * loyalty_member            — the member aggregate: phone_encrypted/phone_hash (rule 6 PII,
--                                 AES-256-GCM BYTEA + a SEPARATE HMAC-SHA256 exact-match hash),
--                                 display_name_encrypted (optional PII), points_balance (MAY GO
--                                 NEGATIVE — flag-on-overdraft policy, see EarnRule/ingest docs),
--                                 balance_seq (monotonic, for LoyaltyBalanceChanged set-if-newer).
--   * loyalty_ledger_entry      — append-only points-movement history (EARN/REDEEM/ADJUST/REVERSE),
--                                 UNIQUE(company_id, source_event_id, entry_type) as the idempotency
--                                 backstop behind ProcessedEventStore.
--   * gift_card                 — the stored-value card aggregate: id FROM the selling vertical's
--                                 GiftCardSold event (never generated here), code deterministically
--                                 DERIVED from the id (not PII), balance_minor (MAY GO NEGATIVE, same
--                                 policy as points), balance_seq.
--   * gift_card_ledger_entry    — append-only stored-value movement history (LOAD/REDEEM/REVERSE/
--                                 ADJUST), the same idempotency-backstop shape.
--   * earn_rule                 — company-wide, versioned + effective-dated points-earning
--                                 configuration, mirroring tax_charge_rule's shape (provenance +
--                                 source_note + effective window). NO SEED ROW: earning is
--                                 company-configured — zero-earn until an owner/manager creates the
--                                 first rule via POST /api/v1/loyalty/earn-rules.
--
-- The app connects as a NON-superuser role (without BYPASSRLS), so RLS is genuinely enforced;
-- FORCE ROW LEVEL SECURITY makes the policy apply even to the table owner / migration role, so no
-- connection silently bypasses tenant isolation (CLAUDE.md rule 5 — defense in depth).
-- current_setting('app.current_tenant', true) returns NULL when the GUC is unset, so with no
-- tenant bound every policy predicate is false -> fail closed.

-- ---------------------------------------------------------------------------
-- transactional outbox (consumed by Debezium / StubRelay) — traceparent folded in from day one
-- ---------------------------------------------------------------------------
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
CREATE TABLE processed_event (
    event_id     UUID                     NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- ---------------------------------------------------------------------------
-- loyalty_member — the member aggregate; the ONLY home of member PII (rule 6)
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty_member (
    id                       UUID         NOT NULL PRIMARY KEY,

    -- PII (rule 6): AES-256-GCM ciphertext, random IV per value (see PiiCipher). Stored as BYTEA
    -- (not TEXT — see PiiBytesAttributeConverter for why this differs from employee-service's TEXT
    -- column). NEVER queried directly — phone_hash below is the exact-match lookup key.
    phone_encrypted          BYTEA        NOT NULL,

    -- Deterministic HMAC-SHA256 digest of the normalized phone (a SEPARATE key from the AES cipher
    -- above — see PiiEncryptionConfig). NOT reversible to the plaintext phone. The exact-match
    -- lookup index.
    phone_hash               VARCHAR(64)  NOT NULL,

    -- PII (rule 6), optional. Same AES-256-GCM BYTEA scheme as phone_encrypted.
    display_name_encrypted   BYTEA        NULL,

    -- MAY GO NEGATIVE (flag-on-overdraft policy, ADR 0027): a checkout can accept a redemption
    -- against a vertical's stale local cached balance; loyalty-service's ledger applies the
    -- authoritative delta anyway rather than silently drop it or block synchronously (rule 2), and
    -- raises LoyaltyRedemptionFlagged for manual follow-up. See LoyaltyMember#applyPointsDelta.
    points_balance           BIGINT       NOT NULL DEFAULT 0,

    -- Monotonically increasing per-member sequence, bumped on every balance-affecting write; carried
    -- ABSOLUTE (not delta) on LoyaltyBalanceChanged (set-if-newer discipline, ARCHITECTURE.md §3).
    balance_seq              BIGINT       NOT NULL DEFAULT 0,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at               TIMESTAMPTZ  NOT NULL,
    created_by                VARCHAR(255) NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    updated_by                VARCHAR(255) NOT NULL,
    version                   BIGINT       NOT NULL,
    company_id                VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_loyalty_member_company_phone_hash UNIQUE (company_id, phone_hash)
);

ALTER TABLE loyalty_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE loyalty_member FORCE ROW LEVEL SECURITY;

CREATE POLICY loyalty_member_tenant_isolation ON loyalty_member
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- loyalty_ledger_entry — append-only points-movement history
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty_ledger_entry (
    id               UUID         NOT NULL PRIMARY KEY,
    member_id        UUID         NOT NULL,

    entry_type       VARCHAR(16)  NOT NULL,

    -- Positive for EARN, negative for REDEEM/REVERSE (either sign for a manual ADJUST). Never a
    -- float (rule 8).
    points_delta     BIGINT       NOT NULL,

    -- Optional currency-value context: the redeemed value on a REDEEM entry, or (informationally)
    -- the taxable base an EARN entry was computed against. NULL for a points-only ADJUST/REVERSE.
    value_minor      BIGINT       NULL,
    currency         CHAR(3)      NULL,

    -- The sale this entry is attributed to, or NULL for an out-of-band manual adjustment.
    sale_id          UUID         NULL,

    -- The causing event's durable id — the idempotency backstop key (paired with entry_type below).
    source_event_id  UUID         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(255) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(255) NOT NULL,
    version            BIGINT       NOT NULL,
    company_id         VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_loyalty_ledger_entry_type
        CHECK (entry_type IN ('EARN', 'REDEEM', 'ADJUST', 'REVERSE')),

    -- The idempotency backstop (belt-and-suspenders behind ProcessedEventStore.processOnce): one
    -- row per (causing event, entry kind). A sale that BOTH redeems and earns writes two rows —
    -- same source_event_id, different entry_type — which this composite key permits.
    CONSTRAINT uq_loyalty_ledger_entry_source_event
        UNIQUE (company_id, source_event_id, entry_type)
);

ALTER TABLE loyalty_ledger_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE loyalty_ledger_entry FORCE ROW LEVEL SECURITY;

CREATE POLICY loyalty_ledger_entry_tenant_isolation ON loyalty_ledger_entry
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot access paths: a member's ledger history, and the reversal writer's "entries for this sale".
CREATE INDEX idx_loyalty_ledger_entry_member ON loyalty_ledger_entry (company_id, member_id);
CREATE INDEX idx_loyalty_ledger_entry_sale
    ON loyalty_ledger_entry (company_id, sale_id) WHERE sale_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- gift_card — the stored-value card aggregate; id is minted by the selling vertical (the event),
-- never generated here.
-- ---------------------------------------------------------------------------
CREATE TABLE gift_card (
    id                 UUID         NOT NULL PRIMARY KEY,

    -- Derived deterministically from `id` (GiftCardCodeGenerator) — NOT PII, a printable token.
    code               VARCHAR(24)  NOT NULL,

    state              VARCHAR(16)  NOT NULL,

    -- MAY GO NEGATIVE (same flag-on-overdraft policy as loyalty_member.points_balance). Never a
    -- float (rule 8).
    balance_minor      BIGINT       NOT NULL,
    currency           CHAR(3)      NOT NULL,

    -- Monotonically increasing per-card sequence, bumped on every balance-affecting write; carried
    -- ABSOLUTE on GiftCardStateChanged (set-if-newer discipline).
    balance_seq        BIGINT       NOT NULL DEFAULT 0,

    sold_at            TIMESTAMPTZ  NOT NULL,
    sold_business_id   UUID         NOT NULL,

    -- Reserved for a future expiry sweep; not enforced by this wave's ingest logic.
    expires_at         TIMESTAMPTZ  NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at          TIMESTAMPTZ  NOT NULL,
    created_by           VARCHAR(255) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    updated_by           VARCHAR(255) NOT NULL,
    version              BIGINT       NOT NULL,
    company_id           VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_gift_card_state CHECK (state IN ('ACTIVE', 'DEPLETED', 'EXPIRED', 'VOID')),
    CONSTRAINT uq_gift_card_company_code UNIQUE (company_id, code)
);

ALTER TABLE gift_card ENABLE ROW LEVEL SECURITY;
ALTER TABLE gift_card FORCE ROW LEVEL SECURITY;

CREATE POLICY gift_card_tenant_isolation ON gift_card
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot access path: the POS redemption-validation lookup by code.
CREATE INDEX idx_gift_card_code ON gift_card (company_id, code);

-- ---------------------------------------------------------------------------
-- gift_card_ledger_entry — append-only stored-value movement history
-- ---------------------------------------------------------------------------
CREATE TABLE gift_card_ledger_entry (
    id               UUID         NOT NULL PRIMARY KEY,
    gift_card_id     UUID         NOT NULL,

    entry_type       VARCHAR(16)  NOT NULL,

    -- Signed delta in the card currency's minor units (positive LOAD, negative REDEEM, either sign
    -- ADJUST). Never a float (rule 8).
    amount_minor     BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,

    -- The sale this REDEEM/REVERSE entry is attributed to, or NULL for a LOAD/ADJUST.
    sale_id          UUID         NULL,

    -- The causing event's durable id — the idempotency backstop key (paired with entry_type below).
    source_event_id  UUID         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(255) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(255) NOT NULL,
    version            BIGINT       NOT NULL,
    company_id         VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_gift_card_ledger_entry_type
        CHECK (entry_type IN ('LOAD', 'REDEEM', 'REVERSE', 'ADJUST')),

    CONSTRAINT uq_gift_card_ledger_entry_source_event
        UNIQUE (company_id, source_event_id, entry_type)
);

ALTER TABLE gift_card_ledger_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE gift_card_ledger_entry FORCE ROW LEVEL SECURITY;

CREATE POLICY gift_card_ledger_entry_tenant_isolation ON gift_card_ledger_entry
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_gift_card_ledger_entry_card ON gift_card_ledger_entry (company_id, gift_card_id);
CREATE INDEX idx_gift_card_ledger_entry_sale
    ON gift_card_ledger_entry (company_id, sale_id) WHERE sale_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- earn_rule — company-wide, versioned + effective-dated points-earning configuration
-- ---------------------------------------------------------------------------
-- Mirrors tax_charge_rule's shape (provenance + source_note + effective window + highest-version-
-- wins resolution). Unlike tax_charge_rule there is NO rule_key column: earn_rule has exactly ONE
-- rule family (points earning) per company, so rule_version alone disambiguates versions.
--
-- DELIBERATE: NO SEED ROW. Earning is company-configured — a fresh company earns ZERO points on
-- every sale until an owner/manager creates the first row via POST /api/v1/loyalty/earn-rules.
CREATE TABLE earn_rule (
    id                    UUID         NOT NULL PRIMARY KEY,
    rule_version          VARCHAR(64)  NOT NULL,

    -- Points earned per minor unit of the sale currency, in basis points. Never a float (rule 8).
    points_per_minor_bp   BIGINT       NOT NULL,

    -- Optional floor: a sale whose taxable base is below this earns NOTHING (not even a zero-point
    -- ledger row). NULL means no floor.
    min_sale_minor        BIGINT       NULL,

    -- Provenance: ILLUSTRATIVE_PLACEHOLDER or OFFICIAL (CHECK constraint, no default).
    provenance            VARCHAR(32)  NOT NULL,

    -- Human-readable note. Mandatory.
    source_note           TEXT         NOT NULL,

    effective_from        DATE         NOT NULL,
    effective_to          DATE         NOT NULL DEFAULT DATE '9999-12-31',
    active                BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at             TIMESTAMPTZ  NOT NULL,
    created_by              VARCHAR(255) NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    updated_by              VARCHAR(255) NOT NULL,
    version                 BIGINT       NOT NULL,
    company_id               VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_earn_rule_provenance
        CHECK (provenance IN ('ILLUSTRATIVE_PLACEHOLDER', 'OFFICIAL')),
    CONSTRAINT ck_earn_rule_points_per_minor_bp_nonneg
        CHECK (points_per_minor_bp >= 0),
    CONSTRAINT ck_earn_rule_min_sale_minor_nonneg
        CHECK (min_sale_minor IS NULL OR min_sale_minor >= 0),
    CONSTRAINT uq_earn_rule_company_version
        UNIQUE (company_id, rule_version)
);

ALTER TABLE earn_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE earn_rule FORCE ROW LEVEL SECURITY;

CREATE POLICY earn_rule_tenant_isolation ON earn_rule
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Resolution index: find the highest-version active row effective at a given date.
CREATE INDEX idx_earn_rule_resolution
    ON earn_rule (company_id, effective_from, effective_to)
    WHERE active = TRUE;

-- NOTE: no seed INSERT here — see the table comment above (earning is zero until configured).
