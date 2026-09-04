-- finance-service V56 — widen ck_bank_line_category to include QRIS_CLEARING (bugfix, ADR 0045).
--
-- BUG: V52 (ADR 0045, "Settling 1901 to bank") added QRIS_CLEARING as a fourth
-- ReconciliationCategory (id.co.nativeapp.finance.bank.domain.ReconciliationCategory: CLEARING,
-- BANK_FEE, INTEREST, QRIS_CLEARING) that ReconciliationWriter.reconcile(...) can legitimately
-- persist to bank_statement_line.reconciled_category — but V52 only seeded the QRIS_FEE_EXPENSE
-- role/account; it never widened V29's ck_bank_line_category CHECK. Every real
-- reconcile(lineId, QRIS_CLEARING, feeMinor) call has therefore been failing the CHECK constraint
-- and rolling back in prod and in tests since V52 shipped (GlConfigOfficialiseV55Test had to route
-- around it via the pure buildEntry(...) builder instead of the persisted reconcile(...) path).
--
-- Grepped every ReconciliationCategory value used by ReconciliationWriter (the only writer of this
-- column) plus every migration V30-V55 that touches bank reconciliation: no category beyond
-- QRIS_CLEARING is missing — CLEARING/BANK_FEE/INTEREST were already present since V29.
--
-- Purely additive to the allowed IN-list: no existing row (only NULL/CLEARING/BANK_FEE/INTEREST
-- ever COULD have been persisted, since anything else was always rejected) can violate the widened
-- constraint, so this applies cleanly with no data migration. Same drop/recreate idiom as V53's
-- ck_template_amount_basis widen.
ALTER TABLE bank_statement_line DROP CONSTRAINT ck_bank_line_category;

ALTER TABLE bank_statement_line
    ADD CONSTRAINT ck_bank_line_category
        CHECK (reconciled_category IS NULL
               OR reconciled_category IN ('CLEARING', 'BANK_FEE', 'INTEREST', 'QRIS_CLEARING'));
