-- V41: Register-close CORRECTION support (ADR 0064).
--
-- A manager/owner can correct a cash-count mistake on an already-CLOSED session. Finance reverses
-- the prior variance journal and posts the corrected one in its place; to name the prior variance
-- the correction must know the outbox event id finance keyed it on (close_event_id), and it stamps a
-- monotonic sequence (close_seq) for audit/display.
--
-- Additive, backfill-free:
--   * close_seq defaults to 1 — every existing close is "the original" (ADD COLUMN ... DEFAULT is a
--     metadata-only change in Postgres, no table rewrite).
--   * close_event_id is NULL for sessions closed BEFORE this migration; they carry no reversible
--     event, so the correction path rejects them with 422 (an accountant's adjusting entry is the
--     fix). No DML UPDATE on this FORCE-RLS table, so no RLS-visibility backfill gotcha.

ALTER TABLE cash_register_session
  ADD COLUMN close_seq      INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN close_event_id UUID;

COMMENT ON COLUMN cash_register_session.close_seq IS
  'ADR 0064: 1 for the original close, +1 per manager/owner cash correction (audit/display).';
COMMENT ON COLUMN cash_register_session.close_event_id IS
  'ADR 0064: outbox event id of the most recent close/correction — finance keys its variance journal on this and a correction supersedes it. NULL for pre-0064 closes (not correctable).';
