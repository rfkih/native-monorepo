-- ============================================================================================================================
-- V65 — give the QRIS/card sub-ledger the balance the GL has been carrying all along (ADR 0076)
-- ============================================================================================================================
-- V61–V64 widened the receivable sub-ledger from ONLINE to every tender someone else settles, and
-- the accrual starts working the moment that code is live. It has no memory: every QRIS sale made
-- BEFORE the deploy debited QRIS_CLEARING (1901) in the general ledger and wrote no sub-ledger row,
-- because there was no sub-ledger row to write.
--
-- The payout screen reads the sub-ledger, not the ledger. So a merchant who has been taking QRIS for
-- months opens it and sees nothing to settle, while their money sits in 1901 — which is exactly what
-- happened on the first tenant to try it (Rp 222.000 across 12 legs, invisible).
--
-- Marketplace did not have this problem: `platform_receivable` has carried per-channel balances
-- since ADR 0036, so V61 only had to name their payer. QRIS and card had nothing to carry forward.
--
-- ---------------------------------------------------------------------------------------------
-- Why a TRUE-UP is the correct operation, not an accrual
-- ---------------------------------------------------------------------------------------------
-- The sub-ledger is a DIMENSION of a GL account, never an independent truth (V44: "the GL account
-- stays authoritative and company-wide; this table exists only to add the per-channel dimension the
-- GL does not carry"). So setting a tender family's row EQUAL to its account's balance is a
-- statement of fact, not an estimate.
--
-- It is also self-correcting on both edges:
--   * the GL balance is already net of anything settled through bank reconciliation (which CREDITS
--     1901), so what remains is precisely what is still owed;
--   * a sale that accrued in the window between the deploy and this migration is in BOTH the GL
--     balance and the row, and assigning (rather than adding) cannot double-count it.
--
-- Only QRIS and CARD are seeded. Touching MARKETPLACE rows would overwrite a per-channel breakdown
-- the GL cannot reproduce — 1250 is one shared account across every channel — and would collapse
-- GoFood and ShopeeFood into a single meaningless figure.
--
-- Accounts are resolved through `role_account_map`, never hardcoded: an SME may already have
-- remapped QRIS_CLEARING away from the illustrative 1901.
-- ============================================================================================================================

-- journal_entry / journal_line / platform_receivable / settlement_source_config are all FORCE ROW
-- LEVEL SECURITY, and Flyway runs as the table owner with no `app.current_tenant` GUC set — the
-- read would return ZERO rows and the write would fail its WITH CHECK, both silently (the fleet's
-- known migration-backfill gotcha, cf. payment-service V6). Drop FORCE for the seed, restore after.
ALTER TABLE journal_entry            NO FORCE ROW LEVEL SECURITY;
ALTER TABLE journal_line             NO FORCE ROW LEVEL SECURITY;
ALTER TABLE platform_receivable      NO FORCE ROW LEVEL SECURITY;
ALTER TABLE settlement_source_config NO FORCE ROW LEVEL SECURITY;

WITH mapped AS (
    -- The account each tender role points at TODAY: the effective-dated row with the highest
    -- version, mirroring how RoleAccountResolver picks one at runtime.
    SELECT DISTINCT ON (account_role)
           account_role,
           gl_account_code
      FROM role_account_map
     WHERE account_role IN ('QRIS_CLEARING', 'CARD_CLEARING')
       AND CURRENT_DATE BETWEEN effective_from AND effective_to
     ORDER BY account_role, version DESC
),
balances AS (
    -- Debit-normal asset accounts: what is still owed = Σ debits − Σ credits, per tenant and
    -- currency (money never crosses currencies — rule 8).
    SELECT je.company_id,
           jl.currency,
           CASE m.account_role
               WHEN 'QRIS_CLEARING' THEN 'QRIS'
               ELSE 'CARD'
           END                                                        AS source_kind,
           CASE m.account_role
               WHEN 'QRIS_CLEARING' THEN 'TENDER:QRIS'
               ELSE 'TENDER:CARD'
           END                                                        AS channel_code,
           SUM(jl.debit_minor) - SUM(jl.credit_minor)                 AS outstanding_minor
      FROM journal_line jl
      JOIN journal_entry je ON je.id = jl.entry_id
      JOIN mapped m         ON m.gl_account_code = jl.account_code
     GROUP BY je.company_id, jl.currency, m.account_role
    HAVING SUM(jl.debit_minor) - SUM(jl.credit_minor) <> 0
)
INSERT INTO platform_receivable
    (id, channel_code, currency, outstanding_minor, source_kind, source_code,
     created_at, created_by, updated_at, updated_by, version, company_id)
SELECT gen_random_uuid(),
       b.channel_code,
       b.currency,
       b.outstanding_minor,
       b.source_kind,
       -- The payer if the merchant has already named one; otherwise the family settles under its
       -- own name, which is the same default the resolver applies.
       COALESCE(c.source_code, b.source_kind),
       now(),
       'migration:V65',
       now(),
       'migration:V65',
       0,
       b.company_id
  FROM balances b
  LEFT JOIN settlement_source_config c
         ON c.company_id = b.company_id
        AND c.source_kind = b.source_kind
ON CONFLICT (company_id, channel_code, currency) DO UPDATE SET
    outstanding_minor = EXCLUDED.outstanding_minor,
    source_kind       = EXCLUDED.source_kind,
    source_code       = EXCLUDED.source_code,
    updated_at        = now(),
    updated_by        = EXCLUDED.updated_by,
    version           = platform_receivable.version + 1;

ALTER TABLE settlement_source_config FORCE ROW LEVEL SECURITY;
ALTER TABLE platform_receivable      FORCE ROW LEVEL SECURITY;
ALTER TABLE journal_line             FORCE ROW LEVEL SECURITY;
ALTER TABLE journal_entry            FORCE ROW LEVEL SECURITY;

-- ============================================================================================================================
-- After this runs, the invariant the sub-ledger always claimed becomes true for QRIS and card as
-- well as marketplace: each tender family's rows sum to the balance of the GL account they are a
-- dimension of. From here the accrual keeps it that way.
--
-- Card money is seeded but still not settleable through the payout form (no CARD_FEE_EXPENSE role
-- is mapped — ADR 0076). It is seeded anyway so the balance is visible and so the dimension is
-- already correct on the day a card fee account is agreed.
-- ============================================================================================================================
