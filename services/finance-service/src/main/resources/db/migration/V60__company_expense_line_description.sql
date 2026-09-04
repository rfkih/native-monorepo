-- ADR 0072 follow-up (owner request 2026-09-04) — the name printed on the RECEIPT is not the name
-- of the inventory item. A market nota says "AYAM BROILER 1KG"; the ingredient is "Ayam fillet".
-- Recording only the ingredient name loses the link back to the physical document; recording only
-- the receipt text loses which stock item moved. So a line keeps BOTH: `ingredient_name` stays the
-- inventory snapshot, and this column carries the receipt wording when it differs.
--
-- AP bill lines already had this shape (bill_line.description alongside ingredient_id/name, V59) —
-- this brings company-expense lines to parity.
--
-- Nullable + additive (expand-only, rollback-safe per the ADR 0057 deploy gate): existing rows and
-- any client that omits the field behave exactly as before, with the ingredient name doing the
-- display work.

ALTER TABLE company_expense_line
    ADD COLUMN description VARCHAR(500);
