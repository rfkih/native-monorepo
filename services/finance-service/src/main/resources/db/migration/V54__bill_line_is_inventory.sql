-- finance-service V54 — ADR 0067 Phase B, §3: the per-line inventory-routing flag.
--
-- Adds bill_line.is_inventory BOOLEAN NOT NULL DEFAULT FALSE — the optional per-line flag
-- (CreateBillRequest.LineRequest / BillLineInput, next task) that steers BillWriter's net split
-- between EXPENSE_NET (Σ non-inventory lines, unchanged Dr 5000 today) and INVENTORY_NET (Σ
-- inventory-flagged lines, Dr 2050 GRNI — only once BillWriter and the DB-version-3 BILL_POSTED/
-- BILL_VOID templates seeded inert by V53 are activated together in the SAME release, per V53's
-- CRITICAL note). MIGRATION ONLY: no BillWriter change, no CreateBillRequest/BillLineInput field, no
-- BillLine entity mapping here — that Java lands with the writer wiring. DEFAULT FALSE means every
-- existing row backfills to today's behaviour with zero data migration needed, and every new row
-- inserted by the CURRENT (unmodified) BillWriter also lands FALSE — this migration is a pure
-- additive, backward-safe expand with no observable effect until the next task's writer change
-- reads the column.
--
-- No CHECK constraint needed (a BOOLEAN NOT NULL is already fully constrained) and no new index:
-- BillWriter's post-time net split will read is_inventory per-line off the ALREADY-loaded bill_line
-- rows for one bill_id (idx_bill_line_bill, V27) — an in-memory partition of an already-fetched
-- small set, not a new query shape, so no index is warranted.

ALTER TABLE bill_line ADD COLUMN is_inventory BOOLEAN NOT NULL DEFAULT FALSE;
