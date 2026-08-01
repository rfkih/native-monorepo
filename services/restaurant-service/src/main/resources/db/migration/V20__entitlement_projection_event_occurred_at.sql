-- restaurant-service V20 — closes a fail-open-on-reorder gap in `entitlement_projection` (bug-audit
-- FIX 3): `EntitlementGranted`/`EntitlementRevoked` arrive on two SEPARATE Kafka topics with
-- independent consumer lag, and the writer's upsert was pure last-writer-wins (whichever event the
-- consumer happens to apply LAST wins, regardless of which one actually happened last upstream). A
-- Revoked applied before a lagging/redelivered Granted arrives would leave `entitled = true` even
-- though the company was de-entitled — and Phase 6's anonymous self-order gate (V19) hangs off this
-- projection, so a de-entitled company's QR would keep accepting orders.
--
-- Both events already carry a monotonic per-(company, module) timestamp on the wire (`granted_at` /
-- `revoked_at`, epoch millis — see docs/EVENT-CATALOG.md; no Avro/schema change needed here), so the
-- fix is a set-if-newer upsert guard, the same discipline as `member_balance_ref`/`gift_card_ref`'s
-- `balance_seq` guard (V17): `event_occurred_at` stores the stamp of the last-APPLIED event, and the
-- upsert only overwrites when the incoming event's stamp is >= the stored one — an out-of-order
-- redelivery can never regress the projection, regardless of which topic/partition it arrived on.
--
-- Backfill: existing rows predate this column, so they default to `-infinity` — the earliest
-- possible timestamptz — guaranteeing the very next real event for that (company, module) is always
-- ">= stored" and applies normally; no row is ever stuck unable to update.
ALTER TABLE entitlement_projection
    ADD COLUMN event_occurred_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity';
