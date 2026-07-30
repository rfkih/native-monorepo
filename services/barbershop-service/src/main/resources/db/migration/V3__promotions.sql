-- barbershop-service V3 — Phase-3 promotions: the per-vertical promo engine (ADR 0026).
--
-- PURPOSE. Three new tables give the barbershop POS discount rules beyond the single manual
-- ticket-level discount V1 already added (`barbershop_ticket.discount_minor`):
--   1. promo_rule         — the configured catalog of discount rules (percent/amount off the whole
--                            ticket, percent off a line/category, happy-hour windows, coupon-gated).
--   2. coupon             — single-use-or-limited redemption codes bound to one promo_rule.
--   3. applied_promotion  — the per-sale AUDIT TRAIL: a SNAPSHOT of what actually discounted a given
--                            ticket, so later edits to promo_rule (rate change, retirement) never
--                            rewrite history — the audit trail must not move under a live receipt.
--
-- ADR 0026 (per-vertical promo engine) is the design record for this shape; the identical DDL is
-- shipped in restaurant-service (V16), carwash-service (V8), and barbershop-service (this file) —
-- same tables, same columns, same RLS idiom, differing only in the source-aggregate link column name
-- (`ticket_id` here, matching carwash, vs `order_id` in restaurant) and the closing ALTER's target
-- table.
--
-- COMPOSITION RULE (enforced by the application layer at checkout time, not by the schema):
--   1. LINE-SCOPE rules first        — PERCENT_OFF_LINE (and the schema-reserved BUY_X_GET_Y, not
--                                      shipped yet — see the rule_type comment below) apply per
--                                      matching line (ITEM or CATEGORY scope) before any ticket-level
--                                      rule is considered. CATEGORY scope is not meaningful for
--                                      barbershop today (no menu_category-equivalent catalog
--                                      dimension exists here); this service simply never creates a
--                                      CATEGORY-scoped rule — the schema stays identical regardless.
--   2. AUTOMATIC ticket-level rules  — PERCENT_OFF_ORDER / AMOUNT_OFF_ORDER rules that do NOT require
--                                      a coupon (`requires_coupon = FALSE`), evaluated in `priority`
--                                      order (lower number first); a rule marked `exclusive = TRUE`
--                                      that matches stops further automatic rules from stacking.
--   3. ONE coupon                    — at most one redeemed coupon's rule is applied on top (a
--                                      checkout accepts a single coupon code; `coupon.rule_id` decides
--                                      what it does), gated by the coupon's own `expires_at`/`active`/
--                                      `redeemed_count < max_redemptions`.
--   4. MANUAL discount last          — the pre-existing staff-entered ticket-level discount
--                                      (`barbershop_ticket.discount_minor`, V1) is applied last.
--   5. CLAMP                         — the total of every layer above is clamped so the sum never
--                                      exceeds the ticket subtotal (a discount can zero out a sale, it
--                                      can never make it negative).
--
-- FINANCE IS UNCHANGED. Every layer above collapses into the SAME pre-existing
-- `barbershop_ticket.discount_minor` aggregate the checkout already prices from; `SaleRecorded` keeps
-- carrying only the aggregate `discount_minor` it always has. Zero Avro schema changes, zero new
-- events, zero new consumers in finance-service — the promo engine is entirely upstream of the
-- existing money path (rule 8: money stays BIGINT minor units + CHAR(3) currency throughout).
--
-- Every table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a <table>_tenant_isolation
-- policy keyed to app.current_tenant, exactly as V1 in this service (and carwash-service's identical
-- idiom, which this service was cloned from).

-- ---------------------------------------------------------------------------
-- 1. promo_rule — the configured catalog of discount rules
-- ---------------------------------------------------------------------------
CREATE TABLE promo_rule (
    id                  UUID         NOT NULL PRIMARY KEY,
    name                VARCHAR(120) NOT NULL,

    -- PERCENT_OFF_ORDER | AMOUNT_OFF_ORDER | PERCENT_OFF_LINE | BUY_X_GET_Y.
    -- BUY_X_GET_Y is SCHEMA-RESERVED ONLY: the supporting columns (buy_qty / get_qty /
    -- get_scope_ref_id below) exist so a future engine revision can add it without another
    -- migration, but the Phase-3 engine ships WITHOUT it — no create-rule path accepts it and no
    -- evaluator branch handles it yet.
    rule_type           VARCHAR(32)  NOT NULL,

    -- Line-scope rules only (PERCENT_OFF_LINE today; BUY_X_GET_Y when it ships): which catalog
    -- dimension scope_ref_id points into. CATEGORY is only meaningful for restaurant
    -- (menu_category) — verticals without categories (carwash, barbershop) simply never create a
    -- CATEGORY-scoped rule; scope_ref_id is a deliberately opaque UUID (not a FK — no
    -- cross-aggregate FK, rule 1) so the DDL stays byte-identical across all three services either
    -- way.
    scope_kind          VARCHAR(16)  NULL,
    scope_ref_id        UUID         NULL,

    -- PERCENT_OFF_ORDER / PERCENT_OFF_LINE: rate in basis points (1 bp = 0.01%), 0-10000 inclusive.
    rate_bp             BIGINT       NULL,

    -- AMOUNT_OFF_ORDER: a fixed discount. Money (libs/money) = integer minor units + ISO-4217 code,
    -- NEVER a float (rule 8).
    amount_minor        BIGINT       NULL,
    currency            CHAR(3)      NULL,

    -- BUY_X_GET_Y support columns — schema-reserved (see rule_type comment above); unused today.
    buy_qty             INT          NULL,
    get_qty             INT          NULL,
    get_scope_ref_id    UUID         NULL,

    -- Minimum ticket subtotal (minor units, ticket currency) required for this rule to qualify.
    min_subtotal_minor  BIGINT       NULL,

    -- Happy-hour gating. dow_mask: bit 0 = Monday .. bit 6 = Sunday; NULL = every day of the week.
    -- window_start/window_end bound the time-of-day window in `tz`; NULL on either = no time bound.
    dow_mask            SMALLINT     NULL,
    window_start        TIME         NULL,
    window_end          TIME         NULL,
    tz                  VARCHAR(48)  NOT NULL DEFAULT 'Asia/Jakarta',

    -- Lower priority number = evaluated first among automatic (non-coupon) rules (composition
    -- rule 2 above). exclusive = TRUE means a matching rule blocks further automatic rules from
    -- stacking on the same ticket.
    priority            INT          NOT NULL DEFAULT 100,
    exclusive           BOOLEAN      NOT NULL DEFAULT FALSE,

    -- requires_coupon = TRUE means this rule only ever applies via a redeemed `coupon` row pointing
    -- at it (composition rule 3) — it is never picked up as an automatic rule.
    requires_coupon     BOOLEAN      NOT NULL DEFAULT FALSE,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Effective-dating: 9999-12-31 open-ended sentinel, never NULL.
    effective_from       DATE         NOT NULL,
    effective_to         DATE         NOT NULL DEFAULT DATE '9999-12-31',

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at          TIMESTAMPTZ  NOT NULL,
    created_by          VARCHAR(255) NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    updated_by          VARCHAR(255) NOT NULL,
    version             BIGINT       NOT NULL,
    company_id          VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_promo_rule_rule_type
        CHECK (rule_type IN ('PERCENT_OFF_ORDER', 'AMOUNT_OFF_ORDER', 'PERCENT_OFF_LINE', 'BUY_X_GET_Y')),
    CONSTRAINT ck_promo_rule_scope_kind
        CHECK (scope_kind IS NULL OR scope_kind IN ('ITEM', 'CATEGORY')),
    CONSTRAINT ck_promo_rule_rate_bp_range
        CHECK (rate_bp IS NULL OR rate_bp BETWEEN 0 AND 10000),
    CONSTRAINT ck_promo_rule_amount_minor_nonneg
        CHECK (amount_minor IS NULL OR amount_minor >= 0),
    CONSTRAINT ck_promo_rule_effective_range
        CHECK (effective_to >= effective_from)
);

ALTER TABLE promo_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE promo_rule FORCE ROW LEVEL SECURITY;

CREATE POLICY promo_rule_tenant_isolation ON promo_rule
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot access path: checkout resolves the set of currently-active rules for the tenant. Partial index
-- (WHERE active) keeps it small — retired rules fall out of the index entirely, mirroring the
-- tax_charge_rule resolution index (V1) in this same service.
CREATE INDEX idx_promo_rule_active_window
    ON promo_rule (company_id, effective_from, effective_to)
    WHERE active = TRUE;

-- ---------------------------------------------------------------------------
-- 2. coupon — redemption codes bound to one promo_rule
-- ---------------------------------------------------------------------------
CREATE TABLE coupon (
    id                UUID         NOT NULL PRIMARY KEY,

    -- Uppercase-normalized by the service before insert/lookup (e.g. "SAVE10"); the schema does not
    -- enforce case itself.
    code              VARCHAR(40)  NOT NULL,

    rule_id           UUID         NOT NULL REFERENCES promo_rule (id),

    max_redemptions   INT          NOT NULL DEFAULT 1,
    redeemed_count    INT          NOT NULL DEFAULT 0,

    expires_at        TIMESTAMPTZ  NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_coupon_company_code UNIQUE (company_id, code),
    CONSTRAINT ck_coupon_max_redemptions_positive CHECK (max_redemptions > 0),
    CONSTRAINT ck_coupon_redeemed_count_bounds
        CHECK (redeemed_count >= 0 AND redeemed_count <= max_redemptions)
);

ALTER TABLE coupon ENABLE ROW LEVEL SECURITY;
ALTER TABLE coupon FORCE ROW LEVEL SECURITY;

CREATE POLICY coupon_tenant_isolation ON coupon
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- SINGLE-USE / MAX-REDEMPTION ENFORCEMENT IDIOM — no read-modify-write window.
-- Checkout redeems a coupon with ONE atomic statement that is simultaneously the guard and the
-- increment:
--   UPDATE coupon
--      SET redeemed_count = redeemed_count + 1
--    WHERE id = ? AND redeemed_count < max_redemptions AND active
-- Zero rows updated => the coupon was already exhausted / inactive (possibly by a concurrent
-- checkout that got there first) => the service returns 409 Conflict. Because the check
-- (`redeemed_count < max_redemptions AND active`) and the write happen in the same row-locking
-- UPDATE, there is no separate "SELECT count, decide, then UPDATE" step for two concurrent
-- checkouts to race through — the second one simply sees the row the first already advanced and
-- fails the WHERE clause. The UNIQUE (company_id, code) constraint above is what the code-entry
-- lookup itself uses.

-- ---------------------------------------------------------------------------
-- 3. applied_promotion — the per-sale audit trail (rule SNAPSHOT survives later promo_rule edits)
-- ---------------------------------------------------------------------------
CREATE TABLE applied_promotion (
    id                    UUID         NOT NULL PRIMARY KEY,

    -- The barbershop_ticket this promotion applied to. Deliberately a PLAIN UUID with NO REFERENCES
    -- clause: barbershop_ticket lives in this same database (no cross-service rule-1 concern), but
    -- this table follows the house pattern used elsewhere for non-composition, snapshot-style links
    -- (e.g. barbershop_ticket_line.item_id) — applied_promotion is an independent audit trail, not a
    -- CASCADE-owned child of the ticket.
    ticket_id             UUID         NOT NULL,

    -- The sale this promotion's discount ultimately rode on (NULL until the ticket captures a sale —
    -- mirrors barbershop_ticket.sale_id's own async-capture timing for a digital tender).
    sale_id               UUID         NULL,

    -- The promo_rule (and, if coupon-gated, the coupon) that produced this row. Also plain UUIDs, no
    -- REFERENCES: this row is a permanent SNAPSHOT — it must keep meaning exactly what it meant at
    -- application time even if the promo_rule or coupon row is later edited or (administratively)
    -- removed.
    rule_id               UUID         NOT NULL,
    coupon_id             UUID         NULL,

    -- SNAPSHOT of the rule's defining fields at the moment it was applied.
    rule_name_snapshot    VARCHAR(120) NOT NULL,
    rule_type_snapshot    VARCHAR(32)  NOT NULL,
    rate_bp_snapshot      BIGINT       NULL,

    -- The specific barbershop_ticket_line this discount landed on, for a line-scope rule; NULL for a
    -- ticket-level rule.
    line_ref              UUID         NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float (rule 8). The actual
    -- minor-unit amount THIS rule discounted off THIS ticket, already clamped per the composition
    -- rule (never more than what remained of the subtotal at this layer).
    amount_minor          BIGINT       NOT NULL,
    currency              CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            VARCHAR(255) NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    updated_by            VARCHAR(255) NOT NULL,
    version               BIGINT       NOT NULL,
    company_id            VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_applied_promotion_amount_minor_nonneg CHECK (amount_minor >= 0)
);

ALTER TABLE applied_promotion ENABLE ROW LEVEL SECURITY;
ALTER TABLE applied_promotion FORCE ROW LEVEL SECURITY;

CREATE POLICY applied_promotion_tenant_isolation ON applied_promotion
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Access paths: the promotions that rode on a given sale (receipt / finance-adjacent audit query),
-- and the promotions applied to a given ticket (recompute / receipt rendering before a sale exists).
CREATE INDEX idx_applied_promotion_sale ON applied_promotion (company_id, sale_id);
CREATE INDEX idx_applied_promotion_ticket ON applied_promotion (company_id, ticket_id);

-- ---------------------------------------------------------------------------
-- 4. barbershop_ticket.coupon_id — idempotent-replay + receipt display pointer only
-- ---------------------------------------------------------------------------
-- NOT authoritative: the source of truth for what was actually applied (and for how much) is
-- applied_promotion's snapshot rows, which is what discount_minor was computed from. This column is
-- a denormalized convenience so (a) a retried checkout with the same idempotency key can display the
-- same coupon without re-querying applied_promotion, and (b) receipts/UI can show "Coupon: SAVE10"
-- without a join. Nullable: most tickets never use a coupon.
ALTER TABLE barbershop_ticket
    ADD COLUMN coupon_id UUID NULL;
