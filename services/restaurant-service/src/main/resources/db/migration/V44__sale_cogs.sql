-- restaurant-service V44 — sale.cogs_minor / sale.cogs_currency (ADR 0067 Phase C, §1
-- "SaleCogsRecorded"): the per-sale COGS audit anchor.
--
-- WHAT: Σ depleted qty × moving-average unit cost (ADR 0056) at sale time, snapshotted onto the
-- sale row itself the same way sale.amount_minor/currency snapshot revenue (V1). Persisting it
-- here -- rather than only deriving it on demand -- matters because recipes mutate under
-- full-replace (ADR 0050: a recipe_line set can change after the sale that used it), so the
-- ingredient-cost inputs a later recompute would use are NOT guaranteed to match what was
-- actually depleted at sale time. This column is the durable, never-recomputed record of what
-- was actually expensed, and (once the producer-wiring task, NOT this one, wires it up) is also
-- the value folded into the SaleCogsRecorded outbox event in the same transaction as the sale +
-- depletion.
--
-- NULLABLE ON PURPOSE, both columns, no DEFAULT: every pre-Phase-C sale (and any Phase-C sale
-- whose items carry no recipe / deplete no ingredients) has no COGS to record. A NOT NULL or a
-- DEFAULT here would either fail on the existing sale rows or fabricate a false 0-cost COGS for
-- them -- an expand migration must not rewrite or reinterpret history it cannot honestly know.
-- Nothing reads these columns yet (the writer task is separate), so this is a purely additive,
-- behavior-preserving column add -- mirrors V33's sold_by_user_id precedent exactly.
--
-- Money (rule 8): BIGINT minor units, never a float. cogs_currency is CHAR(3) to match
-- sale.currency's own type (V1) -- same ISO-4217 domain, so no reason to diverge. The
-- both-or-neither CHECK mirrors ck_ingredient_cost_both_or_neither (V31): a half-populated COGS
-- (amount without currency, or vice versa) is never a valid state, in NULL or non-NULL rows
-- alike.
--
-- No index: nothing queries by cogs_minor/cogs_currency today (the GL-derived P&L, ADR 0065,
-- reads finance's own journal, not this column; this is restaurant's local audit anchor only).
-- No new RLS work: the existing sale_tenant_isolation policy (V1) covers new columns on the same
-- table automatically -- ADD COLUMN never needs a new policy.
ALTER TABLE sale ADD COLUMN IF NOT EXISTS cogs_minor BIGINT NULL;
ALTER TABLE sale ADD COLUMN IF NOT EXISTS cogs_currency CHAR(3) NULL;

ALTER TABLE sale
    ADD CONSTRAINT ck_sale_cogs_both_or_neither
        CHECK ((cogs_minor IS NULL) = (cogs_currency IS NULL));

COMMENT ON COLUMN sale.cogs_minor IS
    'Cost of goods sold for this sale: Sigma(depleted qty x moving-average unit cost at sale time), minor units (ADR 0056/0067). NULL for pre-Phase-C sales and sales with no recipe depletion.';
COMMENT ON COLUMN sale.cogs_currency IS
    'ISO-4217 currency of cogs_minor. NULL iff cogs_minor is NULL.';
