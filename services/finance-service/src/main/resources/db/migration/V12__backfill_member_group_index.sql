-- finance-service V12 (P3d SEAM 4a follow-up) — BACKFILL member_group_index from group_member.
--
-- ============================================================================================
-- WHY THIS BACKFILL EXISTS. The member->group REVERSE index (member_group_index, added in V10) is
-- maintained GOING FORWARD by the GroupMembershipChanged consumer (GroupReadModelWriter upserts it
-- alongside the LEAD-scoped group_member row). But a membership that was projected into group_member
-- BEFORE V10 introduced the reverse index has NO index row: its GroupMembershipChanged was already
-- claimed by ProcessedEventStore and will NEVER be re-delivered, so the consumer can never populate
-- the reverse index for it. Such a member's within-company close would then resolve ZERO groups and
-- silently emit NO TrialBalancePublished — dropping it from group consolidation with no error. This
-- one-time backfill closes that gap: it copies every existing group_member membership window into
-- member_group_index. Idempotent (ON CONFLICT DO NOTHING) — rows the consumer already wrote (the
-- post-V10 memberships) are left exactly as the consumer maintains them; only the missing (pre-V10)
-- rows are filled. A no-op on a fresh database (group_member is empty), so it costs nothing on a
-- first install and only does work on an upgrade of a database that already had memberships.
--
-- ----------------------------------------------------------------------------------------------
-- THE RLS TRAP (why the NO FORCE / FORCE bracket). group_member is FORCE ROW LEVEL SECURITY, scoped
-- by current_setting('app.current_tenant') (the lead). Flyway runs as the service's OWN role, which
-- OWNS the table — but FORCE makes the policy bind even the owner, and a migration sets NO tenant
-- GUC, so current_setting('app.current_tenant', true) is NULL and the policy filters EVERY row. A
-- plain INSERT ... SELECT FROM group_member during migration would therefore copy NOTHING (a silent
-- empty backfill — the worst outcome: it looks like it ran). member_group_index spans EVERY lead's
-- members (it is deliberately NOT under RLS — cross-tenant reference data, like group_lead), so the
-- backfill must read ALL group_member rows regardless of lead. The owner can briefly drop FORCE on
-- its own table to read every row, do the copy, then restore FORCE — all inside this migration's
-- single transaction (PostgreSQL DDL is transactional: if anything here fails, the NO FORCE is rolled
-- back too, so the table is never left unprotected). FORCE is fully restored before the migration
-- commits.
--
-- OPERATIONAL NOTE (this is the FIRST migration to use NO FORCE). The app, Flyway, and this migration
-- all connect as the SAME table-owning role, so NO FORCE lifts row security for that role table-wide
-- for the duration of the migration transaction. A LIVE instance querying group_member concurrently
-- during that window would see every lead's rows. This is safe under the standard migrate-before-
-- serve model (a deploy runs migrations before routing traffic to the new schema; a rolling/zero-
-- downtime deploy must drain or not-yet-route the migrating instance) and group_member is a non-PII
-- lead-scoped read model — but the deploy MUST gate traffic during migrate, as it already must for
-- any expand/contract migration.
-- ============================================================================================

ALTER TABLE group_member NO FORCE ROW LEVEL SECURITY;

INSERT INTO member_group_index (member_company_id, group_id, effective_from, effective_to)
SELECT member_company_id, group_id, effective_from, effective_to
  FROM group_member
ON CONFLICT (member_company_id, group_id) DO NOTHING;

ALTER TABLE group_member FORCE ROW LEVEL SECURITY;
