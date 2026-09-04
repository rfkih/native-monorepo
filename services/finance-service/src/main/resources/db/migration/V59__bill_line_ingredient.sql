-- ADR 0072 P4 — AP bills join the one-submit purchase program: an inventory-flagged bill line may
-- carry the restaurant ingredient it purchases (opaque cross-service reference + display-name
-- snapshot + base-unit quantity). On POST, lines carrying an ingredient ride the
-- InventoryPurchaseRecorded outbox event (line_id = bill_line.id) so restaurant receives the
-- stock; under the periodic default the inventory net now posts Dr 5100 HPP (owner decision)
-- instead of riding the 5000 template — bills with NO inventory lines keep the template path
-- byte-identical.
--
-- Pure additive (expand-only, rollback-safe — the ADR 0057 deploy gate): three nullable columns +
-- CHECKs. Existing rows are untouched; an old client that omits the fields behaves as before.

ALTER TABLE bill_line
    ADD COLUMN ingredient_id UUID,
    ADD COLUMN ingredient_name VARCHAR(255),
    ADD COLUMN ingredient_qty_base BIGINT;

-- Ingredient linkage rides ONLY on inventory-flagged lines, id and qty travel together, and a
-- linked qty is meaningful. The name snapshot is free-standing display data (may be present
-- whenever the id is).
ALTER TABLE bill_line
    ADD CONSTRAINT chk_bill_line_ingredient_flagged
        CHECK (ingredient_id IS NULL OR is_inventory),
    ADD CONSTRAINT chk_bill_line_ingredient_pair
        CHECK ((ingredient_id IS NULL) = (ingredient_qty_base IS NULL)),
    ADD CONSTRAINT chk_bill_line_ingredient_qty
        CHECK (ingredient_qty_base IS NULL OR ingredient_qty_base > 0);
