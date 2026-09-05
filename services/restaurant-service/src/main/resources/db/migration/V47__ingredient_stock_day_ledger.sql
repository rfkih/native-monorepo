-- restaurant-service V47 — the per-day ingredient stock ledger ("riwayat stok harian").
--
-- V42 introduced `ingredient_usage_day`: ONE bucket (`qty_used`) per (ingredient, day), UPSERTed by
-- the per-sale recipe depletion. That answered "berapa terpakai hari itu" and nothing else — it
-- could not say how much came IN, how often somebody hand-corrected the figure, or where the stock
-- figure actually landed at the end of the day. Those are exactly the questions an owner asks
-- ("rata-rata pemakaian per hari", "berapa kali stok dikoreksi manual") and exactly the inputs the
-- sales-leak detection needs to tell a real leak apart from ordinary, already-explained movement.
--
-- So this migration promotes that table from a usage counter to the full DAILY MOVEMENT LEDGER, one
-- row per (ingredient, calendar day), covering every way stock moves:
--
--     qty_used        recipe depletion at sale time            (V42's original bucket, unchanged)
--     received_qty    goods receipts + manual "tambah stok"    (new)
--     adjustment_qty  SIGNED opname variance + manual "set"    (new)
--     waste_qty       recorded waste / staff meals             (new, reserved — always 0 for now)
--     closing_qty     ingredient.stock_qty after the day's last movement (new)
--
-- plus two counters the owner reads directly: `receipt_count` and `adjustment_count` ("total
-- koreksi manual"). A day with no movement gets NO row, so a reader takes the opening balance from
-- the most recent earlier row's `closing_qty` (a day-gap is a flat line, not a hole).
--
-- WHY A DAILY AGGREGATE AND NOT A PER-MOVEMENT LEDGER. A movement row per sale x per ingredient is
-- the natural audit shape but explodes with sales volume, which is precisely why V42 chose the
-- (ingredient, day) UPSERT in the first place. This migration keeps that decision and its hard-won
-- concurrency discipline intact: writers still UPSERT one ingredient at a time, in ascending
-- ingredient-UUID order, inside the caller's transaction, so the cross-sale deadlock V42's header
-- warns about stays impossible. Nothing here changes the depletion write path's ordering.
--
-- THE RENAME. `ingredient_usage_day` no longer describes a table that carries receipts, corrections
-- and a closing balance, so table, column, constraints, index and RLS policy are all renamed to
-- `ingredient_stock_day` / `stock_date`. This is safe:
--   * RENAME preserves the data, the indexes, the constraints and the RLS policy (no row is
--     rewritten and no policy is dropped) -- unlike a DROP/CREATE, which would silently lose both.
--   * Debezium captures ONLY `public.outbox` on this database (docker/debezium/outbox-connector.json,
--     `table.include.list`), so no connector, publication or replication slot references this table
--     and the rename cannot disturb CDC.
--   * The Java references are updated in the same commit.
--
-- ADDITIVE COLUMNS carry NOT NULL DEFAULT 0 rather than being backfilled: `ingredient_stock_day` is
-- FORCE-RLS, and a Flyway `UPDATE` against a FORCE-RLS table runs unbound (no `app.current_tenant`
-- GUC in a migration session) and silently matches ZERO rows -- the RLS-migration-backfill trap
-- documented in V19/V20/V21. A DEFAULT needs no UPDATE: Postgres applies it to existing rows
-- without a rewrite. `closing_qty` is the one exception and is deliberately NULLABLE: for a
-- pre-V47 row we genuinely do not know where the stock figure landed that day, and inventing a
-- number would be worse than admitting the gap. Readers must treat NULL as "unknown", never as 0.

-- ---------------------------------------------------------------------------
-- 1. Rename the table and its column to match what they now hold
-- ---------------------------------------------------------------------------
ALTER TABLE ingredient_usage_day RENAME TO ingredient_stock_day;
ALTER TABLE ingredient_stock_day RENAME COLUMN usage_date TO stock_date;

-- Renaming the UNIQUE constraint renames its backing index with it.
ALTER TABLE ingredient_stock_day
    RENAME CONSTRAINT uq_ingredient_usage_day TO uq_ingredient_stock_day;
ALTER TABLE ingredient_stock_day
    RENAME CONSTRAINT ck_ingredient_usage_day_qty TO ck_ingredient_stock_day_qty_used;

ALTER INDEX idx_ingredient_usage_day_outlet_date
    RENAME TO idx_ingredient_stock_day_outlet_date;

ALTER POLICY ingredient_usage_day_tenant_isolation ON ingredient_stock_day
    RENAME TO ingredient_stock_day_tenant_isolation;

-- ---------------------------------------------------------------------------
-- 2. The remaining movement buckets + the day's closing balance
-- ---------------------------------------------------------------------------
ALTER TABLE ingredient_stock_day
    ADD COLUMN received_qty     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN adjustment_qty   BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN waste_qty        BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN receipt_count    INT    NOT NULL DEFAULT 0,
    ADD COLUMN adjustment_count INT    NOT NULL DEFAULT 0,
    ADD COLUMN closing_qty      BIGINT NULL;

-- `adjustment_qty` is the ONLY signed bucket (a correction can go up or down); every other bucket
-- is a magnitude and can never be negative. `closing_qty` is a stock figure, which the ingredient's
-- own ck_ingredient_stock_nonneg (V31) already floors at 0.
ALTER TABLE ingredient_stock_day
    ADD CONSTRAINT ck_ingredient_stock_day_received_nonneg  CHECK (received_qty >= 0),
    ADD CONSTRAINT ck_ingredient_stock_day_waste_nonneg     CHECK (waste_qty >= 0),
    ADD CONSTRAINT ck_ingredient_stock_day_counts_nonneg
        CHECK (receipt_count >= 0 AND adjustment_count >= 0),
    ADD CONSTRAINT ck_ingredient_stock_day_closing_nonneg
        CHECK (closing_qty IS NULL OR closing_qty >= 0);

COMMENT ON TABLE ingredient_stock_day IS
    'One row per (ingredient, calendar day) with every stock movement of that day bucketed by kind, plus the closing balance. Asia/Jakarta day attribution (see IngredientDepletionWriter.USAGE_ZONE). A day with no movement has no row: its opening balance is the most recent earlier row closing_qty.';

COMMENT ON COLUMN ingredient_stock_day.qty_used IS
    'Recipe-driven depletion at sale time, as REQUESTED by the recipe -- not floored at the available stock (the stock figure floors separately, in ingredient.stock_qty). So qty_used can exceed what was physically on hand, and that excess is itself a signal: more was consumed than the system believed existed.';

COMMENT ON COLUMN ingredient_stock_day.received_qty IS
    'Stock that came IN that day: goods receipts (purchase-linked or priced) plus manual "tambah stok". Always positive.';

COMMENT ON COLUMN ingredient_stock_day.adjustment_qty IS
    'SIGNED net manual correction that day: stock-opname variance (counted - system) plus any manual "set stok" delta. Negative = the count came up short. This is the bucket that carries already-explained shrinkage, so a leak report can exclude it from "unexplained".';

COMMENT ON COLUMN ingredient_stock_day.waste_qty IS
    'Recorded waste / spoilage / staff meals. RESERVED: always 0 until the waste-log feature lands; it exists now so the ledger shape does not change under readers later.';

COMMENT ON COLUMN ingredient_stock_day.receipt_count IS
    'How many separate receive events landed that day (not the quantity).';

COMMENT ON COLUMN ingredient_stock_day.adjustment_count IS
    'How many separate manual corrections were made that day -- "berapa kali stok dikoreksi manual". A high count on one ingredient is worth a look regardless of the net quantity, which can average out to nearly zero.';

COMMENT ON COLUMN ingredient_stock_day.closing_qty IS
    'ingredient.stock_qty immediately after the day LAST recorded movement. NULL for pre-V47 rows (genuinely unknown -- never read a NULL as 0).';
