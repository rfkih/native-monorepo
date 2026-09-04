-- finance-service V57 — ADR 0067 Phase D4/D5: perpetual-inventory ACTIVATION.
--
-- Extends inventory_method_config (V53, shipped EMPTY with no writer — this task ships the ONLY
-- writer) with the columns InventoryMethodActivationWriter needs to record WHAT an owner activated
-- and to support a safe Idempotency-Key replay, mirroring company_opening_balance's (V46) once-only
-- + idempotency shape:
--
--   currency                       — ISO-4217 code the opening-inventory journal entry (if any)
--                                     posted in; also the status read's "currency" fallback when no
--                                     GL line has posted to 1100 yet (a zero-value activation).
--   idempotency_key                — the REQUIRED Idempotency-Key header the activation replay
--                                     probe compares (same key + same payload -> 200 replay;
--                                     anything else against an ALREADY-active company -> 409 —
--                                     activation is once-only per company, there is no
--                                     deactivate/amend flow).
--   opening_inventory_value_minor  — the exact activation payload's opening value, in minor units
--                                     (never a float — rule 8), so a replay can verify it matches.
--   opening_entry_id               — the posted Dr 1100 / Cr 3900 journal_entry id, or NULL when
--                                     openingInventoryValueMinor was 0 (no journal booked — ADR 0067
--                                     Phase D: "if openingValue==0, skip the journal but still
--                                     activate").
--
-- All four are added NOT NULL with no DEFAULT: safe because inventory_method_config has ZERO rows in
-- every environment today (V53 shipped it empty; no writer has EVER existed until this task) — an
-- ADD COLUMN ... NOT NULL with no DEFAULT is valid on Postgres precisely because the table is empty
-- (no existing row could violate it). uq_inventory_method_config_company (V53) remains the once-only
-- backstop; no separate unique index is needed on idempotency_key (there is at most one row per
-- company regardless of key).
ALTER TABLE inventory_method_config
    ADD COLUMN currency                      VARCHAR(3)  NOT NULL,
    ADD COLUMN idempotency_key               VARCHAR(64) NOT NULL,
    ADD COLUMN opening_inventory_value_minor BIGINT      NOT NULL,
    ADD COLUMN opening_entry_id              UUID;
