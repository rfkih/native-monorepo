-- finance-service V55 — Officialise the three remaining ILLUSTRATIVE role mappings (bucket A of the
-- go-live SME review): QRIS_FEE_EXPENSE (5720), GRNI_CLEARING (2050), COGS (5100).
--
-- V51 (ADR 0042, go-live) declared the chart + every role_account_map/posting_template row official
-- that existed AT THAT TIME. QRIS_FEE_EXPENSE (V52) and GRNI_CLEARING/COGS (V53) were seeded AFTER
-- V51 as brand-new, deliberately illustrative version-1 rows — V51's own text says the provenance
-- machinery "remains so a future unverified change can flag itself again". The SME review has now
-- classified these three as bucket A — structurally unambiguous, no account code and no rate change
-- needed, safe to officialise immediately (unlike bucket B's tax/service-charge/discount items,
-- which stay out of scope here). This migration follows V51's EXACT supersession pattern: append a
-- NEW role_account_map row at version + 1, uses_illustrative = FALSE, same gl_account_code and
-- effective window as the version-1 row it supersedes. Nothing is edited or deleted;
-- RoleAccountResolver's `ORDER BY version DESC` picks the new row automatically, and the version-1
-- illustrative rows remain as an audit trail of what the books used before this go-live extension.
--
-- Money/accounts/rates are UNCHANGED — this flips ONLY provenance. GRNI_CLEARING and COGS are still
-- dormant (ADR 0067 Phase 0 — no tenant has perpetual inventory active, and the BILL_POSTED/
-- BILL_VOID version-3 templates that would ever route to them stay future-dated at 2099-01-01, V53,
-- untouched by this migration); QRIS_FEE_EXPENSE is live on the QRIS-fee reconciliation leg
-- (ReconciliationWriter/V52) — a resolved posting there simply stops stamping
-- journal_entry.uses_illustrative_rules = TRUE. No tenant's amounts move; the only observable effect
-- is the "estimasi/ilustratif" statement badge no longer lighting on these three roles' postings.
--
-- Explicitly OUT of scope (per the go-live SME review, bucket B / deliberately-inert items): the
-- BILL_POSTED/BILL_VOID version-3 posting templates (V53) stay untouched at their 2099-01-01
-- effective_from — the CRITICAL note in V53's header explains why activating them early (before the
-- matching BillWriter net-split ships in the same release) reintroduces an unbalanced-entry
-- regression; officialising the role mappings they WOULD reference does not touch that date. Every
-- other role_account_map / posting_template / chart_of_account row was already officialised by V51.

-- 1. Reword the two illustrative chart_of_account names this migration officialises — same regex V51
--    used to strip the trailing "(ILLUSTRATIVE ...)" / "(PLACEHOLDER ...)" parenthetical. 5100 (COGS)
--    was already clean at seed time (V2) and is left alone; 1100/5800/1900 were already reworded by
--    V51.
UPDATE chart_of_account
   SET name = regexp_replace(name, '\s*\([^)]*(ILLUSTRATIVE|PLACEHOLDER)[^)]*\)\s*$', '')
 WHERE account_code IN ('5720', '2050')
   AND name ~ '(ILLUSTRATIVE|PLACEHOLDER)';

-- 2. Supersede QRIS_FEE_EXPENSE / GRNI_CLEARING / COGS at their highest (illustrative) version with
--    an identical uses_illustrative = FALSE copy at version + 1 (same account_code, same effective
--    window). Scoped to exactly these three roles — every other role was already officialised by
--    V51, and this migration must not touch them.
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
SELECT gen_random_uuid(),
       r.account_role,
       r.gl_account_code,
       r.version + 1,
       FALSE,
       r.effective_from,
       r.effective_to
  FROM role_account_map r
 WHERE r.account_role IN ('QRIS_FEE_EXPENSE', 'GRNI_CLEARING', 'COGS')
   AND r.version = (SELECT MAX(v.version)
                      FROM role_account_map v
                     WHERE v.account_role = r.account_role)
   AND r.uses_illustrative = TRUE;
