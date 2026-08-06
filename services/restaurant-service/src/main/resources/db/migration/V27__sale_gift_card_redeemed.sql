-- restaurant-service V27 — daily close v2, part 3: the gift-card portion of a sale (ADR 0038 phase 2,
-- code-review C1).
--
-- The per-tender close reconciliation must compare against what actually accrued in each NON-cash
-- tender's clearing account, which finance debits with the NET-tender leg (grand total − gift-card
-- redeemed), NOT the gross grand total. Cash already nets this via `cash_collected_minor` (V22); the
-- non-cash tenders had no equivalent, so a card/QRIS sale that carried a gift-card split would post a
-- phantom short at close. This column records the gift-card-redeemed portion of the sale so the close
-- can compute the net (`amount_minor − gift_card_redeemed_minor`) per non-cash tender.
--
-- NOT NULL DEFAULT 0 back-fills every existing row via Postgres's metadata default (no heap rewrite,
-- no UPDATE) — the ADR-0037 V47 trick — so the FORCE-RLS Flyway-UPDATE trap does not apply. Legacy
-- rows read as 0 (no gift card), which is correct for the overwhelming majority; a legacy non-cash
-- sale that DID carry a gift card is the only residual approximation, and it cannot be reconstructed
-- (the gift-card selection lived on restaurant_order, not sale). Every post-V27 write supplies the
-- real value. Money is minor units (rule 8).
ALTER TABLE sale
    ADD COLUMN gift_card_redeemed_minor BIGINT NOT NULL DEFAULT 0;
