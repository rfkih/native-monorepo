-- finance-service V49 — bank_account.bank_name: the bank INSTITUTION (e.g. "BCA", "Bank Mandiri"),
-- picked from the console's curated dropdown ("Other" allows free text). Distinct from `name`,
-- which is the ACCOUNT's own name/label (holder or ledger label) — the console now presents the
-- two as separate fields (UAT feedback 2026-08-05).
--
-- Nullable + additive only: existing rows keep bank_name NULL (rendered as "—"); no UPDATE runs,
-- so the FORCE-RLS silent-zero-match backfill gotcha does not apply. NOT the PII column —
-- account_number stays the masked last-4 (rule 6); a bank's brand name is public data.
ALTER TABLE bank_account ADD COLUMN bank_name VARCHAR(64);
