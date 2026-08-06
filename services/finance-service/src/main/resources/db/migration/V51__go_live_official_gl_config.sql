-- V51 — Go-live: declare the chart of accounts + GL mappings OFFICIAL (ADR 0042).
--
-- Since the accounting core was built, every seeded chart_of_account name carried an
-- "(ILLUSTRATIVE …)" / "(PLACEHOLDER …)" suffix, and every role_account_map + posting_template
-- row shipped version-1 with uses_illustrative = TRUE — an honest signal that the figures were
-- reasonable defaults, not accountant-verified production values. The business owner has now
-- confirmed the chart + mappings as production. This migration flips the DATA to verified;
-- the uses_illustrative provenance machinery itself is kept intact (a future unverified change
-- can still flag itself).
--
-- These three tables are GLOBAL reference data (no company_id, no RLS — see V2/V13), so a single
-- migration reaches every tenant. Nothing is deleted: we reword the display names in place and
-- SUPERSEDE each mapping/template with an identical row at the next version carrying
-- uses_illustrative = FALSE. The resolvers (RoleAccountResolver, PostingTemplateResolver:
-- ORDER BY version DESC) prefer the new rows automatically; the version-1 illustrative rows remain
-- as an audit trail of what the books used before go-live. Account codes, effective dates, and
-- template lines are unchanged — only the provenance flag and the display names move.

-- 1. Reword the chart of accounts: drop the trailing "(ILLUSTRATIVE …)" / "(PLACEHOLDER …)"
--    parenthetical from every account name (the parenthetical is the only illustrative marker on
--    chart_of_account, which has no version/flag column).
UPDATE chart_of_account
   SET name = regexp_replace(name, '\s*\([^)]*(ILLUSTRATIVE|PLACEHOLDER)[^)]*\)\s*$', '')
 WHERE name ~ '(ILLUSTRATIVE|PLACEHOLDER)';

-- Account 1900 is the one account whose BASE name (not just a trailing parenthetical) carries the
-- word "Illustrative" — "Illustrative Cash/Bank Clearing (PLACEHOLDER …)" (V13) — so the regex above
-- only strips its parenthetical and would leave "Illustrative Cash/Bank Clearing" on the trial
-- balance. Reword it explicitly to the clean production name.
UPDATE chart_of_account
   SET name = 'Cash/Bank Clearing'
 WHERE account_code = '1900';

-- 2. Supersede every role_account_map at its highest version with an identical uses_illustrative
--    = FALSE copy at version + 1 (same account_code, same effective window). One new row per role.
--    NB: this copies the highest illustrative row's [effective_from, effective_to] — correct because
--    every current window (2000/2026-01-01 … 9999-12-31) covers the go-live date; a future flip that
--    supersedes a narrower/future-dated illustrative row would need to re-check that window.
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
 WHERE r.version = (SELECT MAX(v.version)
                      FROM role_account_map v
                     WHERE v.account_role = r.account_role)
   AND r.uses_illustrative = TRUE;

-- 3. Supersede every posting_template at its highest version with an identical uses_illustrative
--    = FALSE header at version + 1, copying its lines verbatim onto the new template id.
WITH latest AS (
    SELECT pt.id, pt.event_kind, pt.version, pt.effective_from, pt.effective_to
      FROM posting_template pt
     WHERE pt.version = (SELECT MAX(p2.version)
                           FROM posting_template p2
                          WHERE p2.event_kind = pt.event_kind)
       AND pt.uses_illustrative = TRUE
),
newt AS (
    INSERT INTO posting_template
        (id, event_kind, version, uses_illustrative, effective_from, effective_to)
    SELECT gen_random_uuid(), event_kind, version + 1, FALSE, effective_from, effective_to
      FROM latest
    RETURNING id, event_kind
)
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), newt.id, l.line_no, l.account_role, l.side, l.amount_basis
  FROM newt
  JOIN latest        ON latest.event_kind = newt.event_kind
  JOIN posting_template_line l ON l.template_id = latest.id;
