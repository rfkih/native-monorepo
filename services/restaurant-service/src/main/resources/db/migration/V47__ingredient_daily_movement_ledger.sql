-- restaurant-service V47 — `ingredient_usage_day` becomes the full daily MOVEMENT ledger.
--
-- V42 created this table with ONE bucket (`qty_used`), UPSERTed by the per-sale recipe depletion.
-- That answered "berapa terpakai hari itu" and nothing else — it could not say how much came IN,
-- how often somebody hand-corrected the figure, or where the stock figure landed at day's end.
-- Those are exactly the questions an owner asks ("rata-rata pemakaian per hari", "berapa kali stok
-- dikoreksi manual") and exactly the inputs sales-leak detection needs to tell a real leak apart
-- from ordinary, already-explained movement.
--
-- So this migration widens the table into the full daily ledger, one row per (ingredient, day):
--
--     qty_used        recipe depletion at sale time            (V42's original bucket, unchanged)
--     received_qty    goods receipts + manual "tambah stok"    (new)
--     adjustment_qty  SIGNED opname variance + manual "set"    (new)
--     waste_qty       recorded waste / staff meals             (new, reserved — always 0 for now)
--     closing_qty     ingredient.stock_qty after the day's last movement (new)
--
-- plus two counters the owner reads directly: `receipt_count` and `adjustment_count`. A day with no
-- movement gets NO row, so a reader takes the opening balance from the most recent earlier row's
-- `closing_qty` (a day-gap is a flat line, not a hole).
--
-- WHY THE TABLE IS NOT RENAMED, though `ingredient_usage_day` now undersells what it holds. The
-- first draft of this migration renamed it to `ingredient_stock_day` for accuracy. That is
-- NON-BACKWARD-COMPATIBLE DDL and `scripts/check-migration-safety.sh` was right to refuse it
-- (ADR 0057): the fleet deploys by ROLLING update with an automatic rollback on a failed health
-- gate, so old and new app versions run against this schema at the same time, and a rollback puts
-- the previous image back in front of a table whose name it has never heard of. The rename would
-- have traded a working rollback for a better noun. The Java side keeps the precise names —
-- `IngredientStockDay`, `stockDate` — mapped onto these historical column names, so the code reads
-- correctly without the schema having to move. Renaming can happen later behind expand/contract if
-- it is ever worth its own release.
--
-- ADDITIVE COLUMNS carry NOT NULL DEFAULT 0 rather than being backfilled: `ingredient_usage_day` is
-- FORCE-RLS, and a Flyway `UPDATE` against a FORCE-RLS table runs unbound (no `app.current_tenant`
-- GUC in a migration session) and silently matches ZERO rows -- the RLS-migration-backfill trap
-- documented in V19/V20/V21. A DEFAULT needs no UPDATE: Postgres applies it to existing rows
-- without a rewrite. `closing_qty` is the one exception and is deliberately NULLABLE: for a
-- pre-V47 row we genuinely do not know where the stock figure landed that day, and inventing a
-- number would be worse than admitting the gap. Readers must treat NULL as "unknown", never as 0.

ALTER TABLE ingredient_usage_day
    ADD COLUMN received_qty     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN adjustment_qty   BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN waste_qty        BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN receipt_count    INT    NOT NULL DEFAULT 0,
    ADD COLUMN adjustment_count INT    NOT NULL DEFAULT 0,
    ADD COLUMN closing_qty      BIGINT NULL;

-- `adjustment_qty` is the ONLY signed bucket (a correction can go up or down); every other bucket
-- is a magnitude and can never be negative. `closing_qty` is a stock figure, which the ingredient's
-- own ck_ingredient_stock_nonneg (V31) already floors at 0.
ALTER TABLE ingredient_usage_day
    ADD CONSTRAINT ck_ingredient_usage_day_received_nonneg  CHECK (received_qty >= 0),
    ADD CONSTRAINT ck_ingredient_usage_day_waste_nonneg     CHECK (waste_qty >= 0),
    ADD CONSTRAINT ck_ingredient_usage_day_counts_nonneg
        CHECK (receipt_count >= 0 AND adjustment_count >= 0),
    ADD CONSTRAINT ck_ingredient_usage_day_closing_nonneg
        CHECK (closing_qty IS NULL OR closing_qty >= 0);

COMMENT ON TABLE ingredient_usage_day IS
    'Despite the historical name (V42, when it held usage only), this is the full DAILY MOVEMENT LEDGER: one row per (ingredient, calendar day) with every stock movement of that day bucketed by kind, plus the closing balance. Asia/Jakarta day attribution (see OutletZone). A day with no movement has no row: its opening balance is the most recent earlier row closing_qty. Not renamed because a rename is non-backward-compatible DDL and would break app-tier rollback (ADR 0057).';

COMMENT ON COLUMN ingredient_usage_day.qty_used IS
    'Recipe-driven depletion at sale time, as REQUESTED by the recipe -- not floored at the available stock (the stock figure floors separately, in ingredient.stock_qty). So qty_used can exceed what was physically on hand, and that excess is itself a signal: more was consumed than the system believed existed.';

COMMENT ON COLUMN ingredient_usage_day.received_qty IS
    'Stock that came IN that day: goods receipts (purchase-linked or priced) plus manual "tambah stok". Always positive.';

COMMENT ON COLUMN ingredient_usage_day.adjustment_qty IS
    'SIGNED net manual correction that day: stock-opname variance (counted - system) plus any manual "set stok" delta. Negative = the count came up short. This is the bucket that carries already-explained shrinkage, so a leak report can exclude it from "unexplained".';

COMMENT ON COLUMN ingredient_usage_day.waste_qty IS
    'Recorded waste / spoilage / staff meals. RESERVED: always 0 until the waste-log feature lands; it exists now so the ledger shape does not change under readers later.';

COMMENT ON COLUMN ingredient_usage_day.receipt_count IS
    'How many separate receive events landed that day (not the quantity).';

COMMENT ON COLUMN ingredient_usage_day.adjustment_count IS
    'How many separate manual corrections were made that day -- "berapa kali stok dikoreksi manual". A high count on one ingredient is worth a look regardless of the net quantity, which can average out to nearly zero.';

COMMENT ON COLUMN ingredient_usage_day.closing_qty IS
    'ingredient.stock_qty immediately after the day LAST recorded movement. NULL for pre-V47 rows (genuinely unknown -- never read a NULL as 0).';
