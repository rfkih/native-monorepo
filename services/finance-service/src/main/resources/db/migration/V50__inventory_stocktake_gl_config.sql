-- finance-service V50 — Inventory stocktake GL wiring: chart-of-account + role maps (ADR 0038 phase
-- 3, physical inventory stocktake).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V25/V28/V30/V32/V43).
-- ============================================================================================================================
-- Adds the inventory asset account (may already exist — V46 opening-balances seeded the SAME 1100
-- placeholder as its brought-forward-inventory balance-sheet target; ON CONFLICT DO NOTHING keeps
-- this idempotent and avoids a duplicate/error regardless of which migration ran first) and the
-- shrinkage expense account, and maps the INVENTORY / INVENTORY_SHRINKAGE roles
-- StocktakeCompletedEvent already documents (finance.stocktake.messaging.StocktakeCompletedEvent).
-- No posting_template and no event_kind widening: a completed stocktake posts an AD-HOC 2-line
-- JournalEntry built directly by the stocktake writer and resolved via RoleAccountResolver — the
-- Bank/Tax/Asset-disposal/Register-close approach (V30/V32/V36/V43).
--
-- Finance posts ONLY the signed net valued shrinkage (`shrinkage_minor`), never re-touching
-- revenue/COGS-on-sale (this is a periodic stocktake, not perpetual inventory):
--   loss (shrinkage_minor > 0) -> Dr INVENTORY_SHRINKAGE (5800) / Cr INVENTORY (1100)
--   gain (shrinkage_minor < 0) -> Dr INVENTORY (1100) / Cr INVENTORY_SHRINKAGE (5800)
--   zero (shrinkage_minor = 0) -> no journal entry (the stocktake is still marked processed)
--
-- 5800 verified collision-free: grepping every `INSERT INTO chart_of_account` across V2-V49 (the
-- same check V36/V39/V43/V44 performed) shows the seeded 58xx code space is empty (57xx's only entry
-- is 5700 Cash Short Expense V43; 56xx's only entry is 5600 Loss on Asset Disposal V36; 55xx's only
-- entry is 5500 Depreciation Expense V35) — 5800 does not collide with any existing account_code.
-- 1100 was already seeded by V46 (opening balances, 'Inventory (PLACEHOLDER — replace via SME)',
-- ASSET) — the INSERT below targets the SAME code with the SAME account_type, so ON CONFLICT DO
-- NOTHING here is a true no-op, not a silent divergence.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 1100 — Inventory (ASSET). May already exist from V46 (opening balances) — same code, same type;
--         ON CONFLICT DO NOTHING keeps this idempotent regardless of migration order.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1100', 'Inventory (ILLUSTRATIVE — SME-gated)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- 5800 — Inventory Shrinkage Expense (EXPENSE). Debited by a net stocktake LOSS, credited by a net
--         GAIN (found stock).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('5800', 'Inventory Shrinkage Expense (ILLUSTRATIVE — SME-gated)', 'EXPENSE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- role_account_map: INVENTORY → 1100, INVENTORY_SHRINKAGE → 5800 (version 1, illustrative).
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'INVENTORY',           '1100', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'INVENTORY_SHRINKAGE', '5800', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. 1100 and 5800 are illustrative placeholders that must map to the client's real inventory-asset
--    and inventory-shrinkage codes; a real COA may prefer a single netted "inventory variance"
--    account instead of splitting loss/gain — remap both roles to it via a higher-version row, no
--    code change (the GAIN/LOSS_ON_DISPOSAL and CASH_SHORT/OVER precedent).
-- B. shrinkage_minor is TRUSTED service data (like RegisterSessionClosedEvent's over_short_minor) —
--    finance does not recompute the valued shrinkage itself (it has no visibility into the
--    restaurant-service stock ledger); StocktakeCompletedEvent.assertValid() only guards the sign
--    range (Long.MIN_VALUE, so Math.abs stays safe) and a non-blank currency before posting.
-- C. No materiality threshold — every non-zero shrinkage posts, however small. A tolerance band
--    before posting a shrinkage entry is a policy choice, deferred to the SME.
-- ============================================================================================================================
