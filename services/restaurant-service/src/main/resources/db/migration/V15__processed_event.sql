-- restaurant-service V15 — idempotent-consumer dedupe store.
--
-- libs/events ProcessedEventStore records each handled Kafka event UUID here so a
-- re-delivered UserOutletAssignmentChanged (or any future consumed event) is applied
-- at most once.  The claim INSERT and the upsert into user_outlet_assignment_ref commit
-- in the same @Transactional call, so dedupe and side effects are atomic (rule 3 /
-- HR-3).
--
-- This table is NOT Auditable and NOT under RLS — it is consumer infrastructure keyed
-- solely by the globally-unique event UUID (which is not tenant-scoped business data).
-- The event id is unique across all tenants; a duplicate event is dropped regardless
-- of tenant, so tenanting this table would add nothing and would complicate the
-- no-tenant-in-scope bootstrap paths.  Identical structure to every other service's
-- processed_event (finance-service V1, employee-service V1, etc.).

CREATE TABLE IF NOT EXISTS processed_event (
    event_id     UUID                     NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
