-- finance-service V11 (P3d follow-ups #38 + #41) — structural tenancy symmetry on the group_member
-- read model, and a typed multi-currency hold state.
--
-- ============================================================================================
-- #38 — STRUCTURAL TENANCY SYMMETRY ON group_member (mirror org-service group_membership).
--
-- The local group_member read model is RLS-scoped to the group's LEAD company (company_id =
-- lead_company_id), and today only two things keep a raw (non-Hibernate) writer from mis-stamping a
-- member row to the wrong tenant: the RLS WITH CHECK (which only confines the row to the SESSION
-- tenant) and the application binding the group_lead-resolved lead as that tenant before the write.
-- org-service's group_membership ALSO carries a structural ck_group_membership_lead_is_tenant CHECK
-- (company_id = lead_company_id::text), pinning the tenant as a DB invariant. This migration adds the
-- SAME column + CHECK to finance's group_member, so a raw finance writer likewise cannot stamp a
-- member row to a tenant other than the group's lead — defense in depth, symmetric with the producer.
--
-- The lead is already known at every group_member write (it is the bound tenant, resolved from
-- group_lead before the RLS-scoped upsert), so the writer stamps it directly. Pre-existing rows are
-- backfilled from company_id, which IS the lead (the read model has always been lead-scoped).
-- ============================================================================================

-- Add nullable first so the backfill can populate it, then enforce NOT NULL + the CHECK.
ALTER TABLE group_member
    ADD COLUMN lead_company_id UUID;

-- Backfill: company_id is the group's lead (the row has always been lead-scoped), so the lead IS the
-- tenant already stamped on every existing row.
UPDATE group_member
    SET lead_company_id = company_id::uuid
    WHERE lead_company_id IS NULL;

ALTER TABLE group_member
    ALTER COLUMN lead_company_id SET NOT NULL;

-- Structural tenancy guard, mirroring org-service group_membership.ck_group_membership_lead_is_tenant:
-- the member row's tenant (company_id) IS the owning group's lead. A raw writer can never stamp a row
-- to a tenant other than that lead.
ALTER TABLE group_member
    ADD CONSTRAINT ck_group_member_lead_is_tenant
        CHECK (company_id = lead_company_id::text);

-- ============================================================================================
-- #41 — TYPED MULTI-CURRENCY HOLD STATE on consolidation_summary.
--
-- A member that reports MULTI-CURRENCY lines WITHIN ONE TrialBalancePublished (its own functional
-- trial balance is not in a single currency) cannot have a well-defined native double-entry residual
-- (Money.plus across currencies is undefined). The SEAM-3b native-balance gate previously threw a raw
-- MismatchedCurrencyException out of the close (a generic 500). It is now a typed, gated HOLD —
-- MEMBER_TRIAL_BALANCE_MULTICURRENCY — exactly like the other native-balance holds: no consolidation,
-- no CTA sweep, no CLOSED. Add the token to the state CHECK.
--
-- 'MEMBER_TRIAL_BALANCE_MULTICURRENCY' is 34 chars — wider than the V7 VARCHAR(32). Widen the column
-- first, then swap the CHECK to include the new token (matching the entity's @Column(length = 40)).
-- ============================================================================================
ALTER TABLE consolidation_summary
    ALTER COLUMN state TYPE VARCHAR(40);
ALTER TABLE consolidation_summary
    DROP CONSTRAINT ck_consolidation_summary_state;
ALTER TABLE consolidation_summary
    ADD CONSTRAINT ck_consolidation_summary_state
        CHECK (state IN ('CLOSED', 'PENDING_MEMBERS', 'MULTI_CURRENCY_UNSUPPORTED',
                         'MEMBER_TRIAL_BALANCE_UNBALANCED', 'MEMBER_TRIAL_BALANCE_MULTICURRENCY',
                         'INTERCOMPANY_UNRECONCILED', 'PENDING_MEMBERS_WARMING_UP', 'SUPERSEDED'));
