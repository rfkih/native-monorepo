-- ============================================================================================================================
-- V67 — a recorded payout can be taken back (ADR 0076)
-- ============================================================================================================================
-- ADR 0036 made settlements append-only with no way to undo one, and ADR 0076 kept that. The first
-- real use showed why it cannot stay: an owner recorded a card payout with the received amount as
-- ZERO — a reasonable reading of "what reached your bank" when the money had not arrived yet — and
-- the whole Rp 144.000 booked as an acquirer fee. Correct entry, wrong figures, and nothing in the
-- product could reverse it.
--
-- A typo on a money form is not an exceptional event; having no way back is what makes it expensive.
-- The form now refuses that particular shape, but a wrong-but-plausible amount will still happen,
-- and the answer to that has to be a flow rather than a hand-written journal (the fleet rule: never
-- mutate money tables by hand, use the app's correction path).
--
-- ---------------------------------------------------------------------------------------------
-- Still append-only
-- ---------------------------------------------------------------------------------------------
-- Voiding does NOT delete or edit the settlement. It stamps the row and posts a CONTRA journal
-- entry that negates the original legs, exactly as the void/refund reversal does for a sale
-- (ReversalPostingWriter) — so the ledger keeps both the mistake and its correction, which is what
-- an audit trail is for. The sub-ledger balance the payout consumed is handed back at the same time.
--
-- Both columns are NULLABLE with no default: an old image writes settlements without them and keeps
-- working, and a NULL `voided_at` is precisely "not voided".
ALTER TABLE platform_settlement
    ADD COLUMN voided_at     TIMESTAMPTZ,
    ADD COLUMN void_entry_id UUID;

-- Once-only, enforced by the database rather than by a read-then-write race: two concurrent voids
-- of the same payout would otherwise both pass an application check and hand the balance back
-- twice. The writer's guarded UPDATE (`WHERE voided_at IS NULL`) is what actually claims it; this
-- index is the backstop that makes a second claim impossible even if that guard were ever loosened.
CREATE UNIQUE INDEX uq_platform_settlement_void_entry
    ON platform_settlement (void_entry_id)
 WHERE void_entry_id IS NOT NULL;
