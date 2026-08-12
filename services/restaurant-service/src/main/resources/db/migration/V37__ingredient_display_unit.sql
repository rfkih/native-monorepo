-- restaurant-service V37 — ingredient display_unit: lets a weight/volume ingredient be SHOWN to the
-- user in kg/liter while stock_qty keeps counting in a smaller INTEGER base unit (g/ml), so fractional
-- inventory (e.g. 2.5 kg) is representable without ever storing a fractional quantity. Quantities stay
-- INTEGER on purpose: the ArchUnit entitiesHaveNoDecimalMoneyFields rule (HR-8,
-- config/LayeredArchitectureTest.java:334) bans a BigDecimal/float field on any @Entity, and no event
-- carries an ingredient qty (StocktakeCompleted carries valued totals, not raw qty), so there is no
-- Avro impact either.
--
-- display_unit is the unit SHOWN to the user; unit stays the unit stock_qty is COUNTED in. For a
-- weight/volume ingredient: unit = base unit ('g' / 'ml'), display_unit = the display unit ('kg' /
-- 'liter'). The conversion factor is always exactly 1000 (1 kg = 1000 g, 1 L = 1000 ml) — this model
-- supports no other factor. Count units (pcs, pack) and any ingredient already counted directly in a
-- base unit (g, ml) get display_unit = NULL: display == unit, nothing to convert, and nothing to
-- backfill (the new column's default already gives them NULL).
--
-- Money is minor units (BIGINT) + currency (rule 8). This migration does not touch the money SOURCE
-- OF TRUTH: stock_value_minor (V36) is never assigned below — only unit_cost_minor, the DERIVED
-- DISPLAY CACHE, is recomputed against the new base-unit quantity so it keeps reading back the correct
-- per-BASE-unit cost.

-- ---------------------------------------------------------------------------
-- 1. ingredient.display_unit — additive column, default NULL (behavior-preserving: every existing row
--    keeps display == unit, i.e. unaffected, until the backfill below converts the kg/liter rows).
-- ---------------------------------------------------------------------------
ALTER TABLE ingredient ADD COLUMN display_unit VARCHAR(16) NULL;

-- ---------------------------------------------------------------------------
-- 2. Backfill — rewrite whole-kg / whole-liter rows onto the base-unit model so every weight/volume
--    ingredient is uniform (base unit + integer base-unit qty). Today the console only ever wrote
--    WHOLE kg/liter counts (integer stock_qty, no server-side conversion — see V31 and
--    frontend/console/src/features/inventory/ingredientApi.ts), so `unit='kg', stock_qty=2` means
--    exactly 2 kg on hand; `unit='g', stock_qty=2000` is the same 2 kg, losslessly.
--
-- THE RLS TRAP (same as V36) — ingredient is FORCE ROW LEVEL SECURITY (V31), policy-scoped by
-- current_setting('app.current_tenant'). Flyway's migration session sets no such GUC, so with FORCE
-- still on, a plain UPDATE would match ZERO rows in EVERY tenant — a silent empty backfill that Flyway
-- reports as success (the same trap V36 documents). Same bracket as V36: NO FORCE -> UPDATE -> FORCE,
-- all inside this migration's single transaction (PostgreSQL DDL is transactional: a failure anywhere
-- rolls back the NO FORCE too, so the table is never left unprotected; FORCE is fully restored before
-- this migration commits).
-- ---------------------------------------------------------------------------
ALTER TABLE ingredient NO FORCE ROW LEVEL SECURITY;

-- kg -> g. Within one UPDATE, every SET expression reads the OLD row (PostgreSQL single-statement
-- semantics), so `stock_qty * 1000` below is OLD stock_qty * 1000 — the same value being written as
-- the NEW stock_qty in this very statement — and the unit_cost_minor recompute divides by that same
-- new quantity. stock_value_minor is deliberately absent from this SET list: the total value is
-- unchanged, only how it is expressed (qty x per-base-unit cache) changes. The cache recompute has
-- three cases: a NULL cache stays NULL (uncosted); a POSITIVE-qty row derives the per-base cache
-- exactly from value / (new qty); a ZERO-qty COSTED row (value is 0 by the V36 invariant, so it
-- cannot be re-derived from value) instead rescales its retained per-DISPLAY cache down by the fixed
-- 1000 factor — WITHOUT this it would keep the per-kg number while unit flips to g, a 1000x cache
-- that the next costless restock would bake into stock_value_minor (the money source of truth).
UPDATE ingredient
   SET display_unit = 'kg',
       unit = 'g',
       stock_qty = stock_qty * 1000,
       unit_cost_minor = CASE
           WHEN unit_cost_minor IS NULL THEN NULL
           WHEN stock_qty > 0
               THEN round(stock_value_minor::numeric / (stock_qty * 1000))::bigint
           ELSE round(unit_cost_minor::numeric / 1000)::bigint
       END
 WHERE unit = 'kg';

-- liter -> ml. Tolerant of stray 'l' / 'L' variants (none observed in current data — the console only
-- ever writes 'liter' — but a hand-seeded or imported row could use the ISO shorthand). Identical
-- arithmetic to the kg branch above.
UPDATE ingredient
   SET display_unit = 'liter',
       unit = 'ml',
       stock_qty = stock_qty * 1000,
       unit_cost_minor = CASE
           WHEN unit_cost_minor IS NULL THEN NULL
           WHEN stock_qty > 0
               THEN round(stock_value_minor::numeric / (stock_qty * 1000))::bigint
           ELSE round(unit_cost_minor::numeric / 1000)::bigint
       END
 WHERE unit IN ('liter', 'l', 'L');

ALTER TABLE ingredient FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 3. Invariant — added AFTER the backfill (add column -> backfill -> constrain is the fleet-wide
--    ordering; see V36 §3): display_unit is either NULL (display == unit, no conversion) or one of
--    the two supported display units, each paired with its own fixed base unit. This is the ONLY
--    conversion pair this model supports (the factor is always 1000); a third pair is a future expand
--    migration, not a change to this CHECK's shape.
-- ---------------------------------------------------------------------------
ALTER TABLE ingredient
    ADD CONSTRAINT ck_ingredient_display_unit_pairing
        CHECK (
            display_unit IS NULL
            OR (display_unit = 'kg' AND unit = 'g')
            OR (display_unit = 'liter' AND unit = 'ml')
        );
