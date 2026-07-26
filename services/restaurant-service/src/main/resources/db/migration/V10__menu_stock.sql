-- Add per-item stock tracking to menu_item.
--
-- stock_quantity IS NULL  → infinite / untracked (default for all existing + new items).
-- stock_quantity >= 0    → tracked; auto-deducted on POS sale; 0 means sold out.
--
-- Additive only — no existing RLS policy change needed (the existing
-- menu_item_tenant_isolation policy already covers all rows).

ALTER TABLE menu_item ADD COLUMN stock_quantity INTEGER NULL;
