-- ADR 0072 follow-up (owner request 2026-09-04) — the pack a thing is BOUGHT in is not the unit it
-- is COUNTED in. A vendor's nota says "TORTILLA 1 PCS" meaning one pack; the ingredient counts
-- individual tortillas, so 20 must enter stock. The console already lets a purchase line state
-- "isi per kemasan" and multiplies before sending the base quantity; this column remembers the
-- usual value per ingredient so it does not have to be retyped (and mistyped) on every purchase.
--
-- Deliberately a DEFAULT, never a rule: pack sizes differ per brand and supplier ("kadang tiap
-- merek isinya beda"), so a purchase line PRE-FILLS from this and stays free to override it.
-- Nothing derives stock from this column — the purchase sends the already-multiplied quantity —
-- so a stale or wrong value here can never move stock by itself.
--
-- Distinct from display_unit (V37), which is the fixed 1000x kg/g · liter/ml family. A pack size
-- is an arbitrary per-product integer and gets its own column rather than overloading that one.
--
-- Nullable + additive (expand-only, rollback-safe per the ADR 0057 deploy gate): existing rows and
-- any client that omits the field behave exactly as before.

ALTER TABLE ingredient
    ADD COLUMN pack_size INT,
    ADD CONSTRAINT chk_ingredient_pack_size_positive
        CHECK (pack_size IS NULL OR pack_size > 0);
