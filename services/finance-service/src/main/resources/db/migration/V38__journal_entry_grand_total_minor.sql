-- finance-service V38 — add grand_total_minor to journal_entry for a stable reversal grand total.
--
-- Context (bug audit 2026-08-01): the void/refund reversal needs the SALE's GRAND TOTAL (what the
-- customer owed = the tender legs) — the void contra must negate it, and the refund full-vs-partial
-- gate compares against it. Reconstructing it from the GL lines (Σ debits − the SALES_DISCOUNT /
-- LOYALTY_DISCOUNT contra-revenue debits) requires resolving those two account codes via the
-- versioned role_account_map. That resolution is fragile: V37 explicitly anticipates an SME
-- remapping those roles to a real chart of accounts, after which the historical lines carry the OLD
-- code while a reversal-time resolve returns the NEW one — the exclusion misses the leg and the
-- grand total is wrong again (discounted full refunds re-rejected, gift-card voids under-reversed).
--
-- Solution: store the precomputed grand_total_minor on the SALE journal_entry at posting time
-- (set by RevenuePostingWriter from SaleRecorded.amount), exactly as V19 stores net_revenue_minor.
-- The reversal writer reads it back — no account-code resolution, no as-of assumption, no hard-coded
-- contra-role list. NULL for non-SALE entries and for SALE entries predating V38 (those fall back to
-- the line-reconstruction, correct against the current immutable seed).
--
-- This migration is ADDITIVE (never edit an applied migration).

ALTER TABLE journal_entry
    ADD COLUMN grand_total_minor BIGINT NULL;

COMMENT ON COLUMN journal_entry.grand_total_minor IS
    'Precomputed grand total (what the customer owed = the tender legs) for SALE entries only. '
    'Used by the void/refund reversal path: the void contra negates it, the refund full-vs-partial '
    'gate compares against it. NULL for non-SALE entries and for SALE entries predating V38 (fall '
    'back to line reconstruction).';
