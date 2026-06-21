-- finance-service V19 — add net_revenue_minor to journal_entry for exact read-model unwind.
--
-- Context: the SALE posting path accumulates NET revenue (subtotal − discount) into the
-- consolidated_revenue and consolidated_pnl read models. The void/full-refund path must unwind
-- by the SAME net amount so the read model nets to zero.
--
-- Without this column, deriving the net from the GL lines alone requires identifying which credit
-- line is GROSS_REVENUE (subtotal) vs SERVICE_CHARGE_REVENUE or TAX_PAYABLE — but we cannot do
-- that without account-code lookup (which requires the role_account_map reference join), and the
-- account codes are tenant/date-scoped reference data, not hard-coded in Java.
--
-- Solution: store the precomputed net_revenue_minor on the SALE journal_entry at posting time
-- (set by RevenuePostingWriter). The reversal writer reads it back and uses it to unwind the
-- read model. NULL for non-SALE entries (EXPENSE, LABOR, reversal entries themselves) and for
-- SALE entries that predate V19 (legacy); those fall back to the 2-line GROSS template unwind
-- (net == gross for legacy sales).
--
-- This migration is ADDITIVE (never edit an applied migration).

ALTER TABLE journal_entry
    ADD COLUMN net_revenue_minor BIGINT NULL;

COMMENT ON COLUMN journal_entry.net_revenue_minor IS
    'Precomputed net revenue (subtotal − discount) for SALE entries only. '
    'Used by the void/refund reversal path to unwind the consolidated_revenue and '
    'consolidated_pnl read models by exactly the same amount that was accumulated. '
    'NULL for non-SALE entries and for SALE entries predating V19 (fall back to grand total).';
