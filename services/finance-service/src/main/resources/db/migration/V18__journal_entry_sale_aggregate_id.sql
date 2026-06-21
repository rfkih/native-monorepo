-- finance-service V18 — add sale_aggregate_id to journal_entry for void/refund leg lookup.
--
-- Phase 2 void/refund unwind policy:
--   When a SaleVoided or SaleRefunded is consumed, finance reverses the original journal entry's
--   lines exactly (negating each component leg: CLEARING, DISCOUNT, GROSS_REVENUE,
--   SERVICE_CHARGE_REVENUE, TAX_PAYABLE). This requires knowing which journal_entry corresponds
--   to the original SaleRecorded event.
--
--   The link: SaleVoidedEvent carries sale_id (the sale aggregate UUID from restaurant-service).
--   restaurant-service writes the outbox row with aggregate_id = sale_id (the sale UUID).
--   The journal_entry.source_event_id = the outbox row's UUID, which is NOT the sale_id.
--
--   This migration adds a nullable sale_aggregate_id column to journal_entry. RevenuePostingWriter
--   populates it for SALE entries. ReversalPostingWriter queries by sale_aggregate_id to find the
--   original entry's lines and reverse them exactly.
--
--   NULL for non-SALE entries (EXPENSE, LABOR, reversal entries themselves).
--   Indexed for fast lookup at void/refund time.
--
-- This migration is ADDITIVE (never edit an applied migration).

ALTER TABLE journal_entry
    ADD COLUMN sale_aggregate_id UUID NULL;

-- Partial index: non-null only for SALE entries (the only ones we look up by sale_aggregate_id).
CREATE INDEX idx_journal_entry_sale_aggregate
    ON journal_entry (sale_aggregate_id)
    WHERE sale_aggregate_id IS NOT NULL;
