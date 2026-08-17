-- finance-service V53 — ADR 0067 Phase 0: perpetual-inventory contracts + config.
-- SHIP-SAFE, ZERO BEHAVIOUR CHANGE for every tenant (see the CRITICAL note in §4 below).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V28/V39/V50).
-- ============================================================================================================================
-- Adds the GRNI (goods-received-not-invoiced) clearing account (2050), maps the new GRNI_CLEARING
-- and COGS roles, widens the amount_basis CHECK for the Phase B bill-routing split, registers (but
-- does NOT activate) the ADR-0067-§3 INVENTORY_NET-carrying BILL_POSTED/BILL_VOID template, and
-- creates the inventory_method_config company-election table (Auditable + FORCE RLS — the ADR 0067
-- §5 "finance decides, per tenant, per period" seam). No Java besides AccountRole.GRNI_CLEARING/
-- COGS changes in this migration; no consumer, no writer, no BillWriter change — those are Phase
-- B/C/D (ADR 0067 phased roadmap).
--
-- VERSION NUMBERING NOTE: ADR 0067 §3 calls the INVENTORY_NET-carrying template "BILL_POSTED v2",
-- written before accounting for V51 (go-live, ADR 0042). V51 already superseded EVERY event_kind's
-- (illustrative) version 1 with an OFFICIAL version 2 row (identical lines, uses_illustrative =
-- FALSE) — BILL_POSTED and BILL_VOID included (`uq_posting_template_kind_version UNIQUE (event_kind,
-- version)` would reject a literal version-2 insert here; confirmed against the live schema). So the
-- CURRENTLY-effective BILL_POSTED/BILL_VOID template today is already DB version 2 (V51's official
-- copy of the V28 shape), and this migration's new template lands at DB **version 3** — the ADR's
-- conceptual "v2" (the second FUNCTIONAL shape), just not the literal integer. Comments below say
-- "v2 (DB version 3)" throughout to keep both readings unambiguous.
-- ============================================================================================================================
--
-- ============================================================================================================================
-- CRITICAL — the new BILL_POSTED/BILL_VOID template (DB version 3) MUST NOT RESOLVE TODAY.
-- ============================================================================================================================
-- PostingTemplateResolver picks the HIGHEST version whose [effective_from, effective_to] window
-- contains the event date (`ORDER BY version DESC ... WHERE ? BETWEEN effective_from AND
-- effective_to`). BillWriter is UNCHANGED by this migration — it still builds its
-- buildEntryFromBreakdown amountsByBasis map with the V28 keys {GROSS, NET, TAX} only. If version 3
-- (whose lines carry amount_basis EXPENSE_NET / INVENTORY_NET / TAX / GROSS) became the resolved
-- template today, the EXPENSE_NET and INVENTORY_NET lines would find no matching key in that map,
-- default to Money.zero (JournalPostingService#buildEntryFromBreakdown:
-- `amountsByBasis.getOrDefault(..., Money.zero(...))`), and be omitted as zero-amount lines —
-- leaving only the TAX debit against the GROSS credit, an UNBALANCED entry. JournalPostingService
-- would catch the resulting UnbalancedJournalException and silently fall back to a Dr/Cr SUSPENSE
-- (9999) two-liner instead of the real Dr 5000 / Dr 1300 / Cr 2000 posting — a live regression for
-- every tenant posting a bill.
--
-- To prevent this, version 3 is seeded with effective_from = 2099-01-01 (deliberately, comfortably
-- beyond any real event date) so it can NEVER win the `ORDER BY version DESC` resolution while
-- version 2 (V51's official row, window 2000-01-01 .. 9999-12-31) remains in its window. Version 2
-- therefore stays the ONLY effective BILL_POSTED/BILL_VOID template — bill posting is byte-identical
-- to pre-V53 (still Dr 5000 (net) / Dr 1300 (tax) / Cr 2000 (gross), uses_illustrative_rules=false,
-- exactly as ApTenancyIsolationTest already pins) until a LATER migration (ADR 0067 Phase B, shipped
-- in lockstep with the BillWriter EXPENSE_NET/INVENTORY_NET net-split + bill_line.is_inventory
-- column) brings version 3's effective_from forward (or supersedes it with a version-4 row carrying
-- the real cutover date — either is a valid use of this same effective-dating mechanism, V51
-- precedent). Do NOT backdate this seed to "activate early" without shipping the matching BillWriter
-- change in the SAME release.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- 1. New illustrative COA account: 2050 GRNI Clearing (LIABILITY). Verified collision-free:
--    grepping every `INSERT INTO chart_of_account` account_code literal across V1-V52 shows no
--    existing 2050 row. 5100 Cost of Goods Sold already exists (V2, EXPENSE) — NOT re-inserted
--    here; only newly role-mapped below (it was seeded with no double-entry role until now).
-- ---------------------------------------------------------------------------
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2050', 'GRNI Clearing (ILLUSTRATIVE — SME-gated)', 'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. role_account_map: GRNI_CLEARING -> 2050, COGS -> 5100 (version 1, illustrative).
--    EXPENSE -> 5000 (V13), VAT_INPUT -> 1300 (V28), and AP -> 2000 (V28) are already mapped and
--    reused by the v2 templates below.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'GRNI_CLEARING', '2050', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'COGS',          '5100', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- 3. Widen the amount_basis CHECK to add EXPENSE_NET, INVENTORY_NET (keep every existing value —
--    same drop/recreate pattern as V17/V28/V37).
-- ---------------------------------------------------------------------------
ALTER TABLE posting_template_line DROP CONSTRAINT ck_template_amount_basis;

ALTER TABLE posting_template_line
    ADD CONSTRAINT ck_template_amount_basis
        CHECK (amount_basis IN (
            'GROSS', 'GROSS_REVENUE', 'DISCOUNT', 'SERVICE_CHARGE', 'TAX', 'NET',
            'NET_TENDER', 'GIFT_CARD_TENDER', 'LOYALTY_REDEEMED',
            'EXPENSE_NET',    -- Phase 0 (ADR 0067 §3): the non-inventory-line net of a bill.
            'INVENTORY_NET'   -- Phase 0 (ADR 0067 §3): the inventory-flagged-line net of a bill.
        ));

-- ---------------------------------------------------------------------------
-- 4. BILL_POSTED / BILL_VOID — ADR 0067 §3's INVENTORY_NET-carrying shape ("v2" conceptually; DB
--    version 3, see the VERSION NUMBERING NOTE above since V51 already claimed version 2) —
--    REGISTERED, INACTIVE (effective_from is 2099-01-01, so V51's official version 2 remains the
--    only resolvable template today, see the CRITICAL note above). The event_kind CHECK already
--    accepts BILL_POSTED/BILL_VOID (V28) — no widening needed here.
-- ---------------------------------------------------------------------------
INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to) VALUES
    (gen_random_uuid(), 'BILL_POSTED', 3, TRUE, DATE '2099-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'BILL_VOID',   3, TRUE, DATE '2099-01-01', DATE '9999-12-31');

-- BILL_POSTED (DB v3): Dr EXPENSE (EXPENSE_NET) / Dr GRNI_CLEARING (INVENTORY_NET) / Dr VAT_INPUT
--   (TAX) / Cr AP (GROSS = total). A tenant with no inventory-flagged lines has INVENTORY_NET = 0,
--   so the GRNI leg zero-omits (JournalPostingService) and the entry collapses to the current
--   (version 2) shape byte-for-byte — but that only matters once Phase B's BillWriter populates
--   both nets AND this template actually resolves (not yet — see the CRITICAL note above).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'EXPENSE',       'DEBIT',  'EXPENSE_NET'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_POSTED' AND pt.version = 3;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'GRNI_CLEARING', 'DEBIT',  'INVENTORY_NET'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_POSTED' AND pt.version = 3;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 3, 'VAT_INPUT',     'DEBIT',  'TAX'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_POSTED' AND pt.version = 3;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 4, 'AP',            'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_POSTED' AND pt.version = 3;

-- BILL_VOID (DB v3): the exact contra of BILL_POSTED v3 (same line order as V28's BILL_VOID v1: AP
--   debit first, then the contra legs in posting order).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'AP',            'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_VOID' AND pt.version = 3;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'EXPENSE',       'CREDIT', 'EXPENSE_NET'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_VOID' AND pt.version = 3;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 3, 'GRNI_CLEARING', 'CREDIT', 'INVENTORY_NET'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_VOID' AND pt.version = 3;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 4, 'VAT_INPUT',     'CREDIT', 'TAX'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_VOID' AND pt.version = 3;

-- ---------------------------------------------------------------------------
-- 5. inventory_method_config — the finance-owned, PER-COMPANY perpetual-inventory election with a
--    cutover period (ADR 0067 §5). Auditable + FORCE RLS (tenant-scoped), unlike the global
--    reference tables above — mirrors the employee_expense_claim_ledger table+RLS shape (V39; V50
--    added only data, no table, for this migration's illustrative-seed comment style).
--
--    No consumer, no writer, no population in Phase 0 — this migration ships ONLY the table + the
--    RLS policy. Phase D wires the console activation flow that inserts/updates a row per company;
--    Phase B/C's StockReceived/SaleCogsRecorded consumers will read `perpetual_active` (as of the
--    event's period) to decide whether to post or claim-and-no-op. An absent row for a tenant is
--    equivalent to "not perpetual-active" (Phase B/C consumers treat a missing row as inactive, not
--    an error) — no backfill needed for existing companies.
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_method_config (
    id                UUID         NOT NULL PRIMARY KEY,

    -- Reserved for a future non-weighted-average method (ADR 0067 explicitly rules out FIFO/lot
    -- for now — "Out of scope"); a single value today, narrowly CHECK-constrained like every other
    -- enum-shaped column in this schema.
    method            VARCHAR(16)  NOT NULL DEFAULT 'PERPETUAL',

    -- The activation gate Phase B/C consumers will read. Defaults TRUE so a FUTURE row inserted for
    -- a brand-new company (ADR 0067 §5: "new companies: perpetual-active from creation") is active
    -- without the caller having to pass it explicitly; an EXISTING company activates via the Phase
    -- D console setup-gate, which will INSERT (or flip) this row explicitly. Inert in Phase 0: no
    -- writer populates a row yet, so this default has no observable effect until Phase D.
    perpetual_active  BOOLEAN      NOT NULL DEFAULT TRUE,

    -- YYYY-MM the company's perpetual accounting starts from (ADR 0067 §5 "cutover"); NULL until
    -- Phase D's activation flow sets it (a fresh company's cutover is its creation period).
    cutover_period    VARCHAR(7),

    activated_at      TIMESTAMPTZ,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_inventory_method_config_method CHECK (method IN ('PERPETUAL'))
);

ALTER TABLE inventory_method_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_method_config FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_method_config_tenant_isolation ON inventory_method_config
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- One election per tenant; also the natural (and only) access path — every future read is
-- "this tenant's row" (company_id is the leftmost/only key column, so the unique index alone
-- serves the hot path with no separate secondary index needed).
CREATE UNIQUE INDEX uq_inventory_method_config_company ON inventory_method_config (company_id);

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. 2050 GRNI Clearing is an illustrative placeholder that must map to the client's real
--    goods-received-not-invoiced / accrued-purchases clearing code.
-- B. 5100 Cost of Goods Sold (seeded V2, now role-mapped for the first time) must be confirmed as
--    the client's real COGS account before Phase C wires SaleCogsRecorded.
-- C. Whether a tenant should activate perpetual inventory at all is a policy decision gated on the
--    tenant's actual purchasing workflow (ADR 0067 Consequences: "the critical operational risk").
--    inventory_method_config.perpetual_active must never be set TRUE for an existing company
--    without the Phase D activation flow's opening-balance step (ADR 0037 reuse) running first.
-- ============================================================================================================================
