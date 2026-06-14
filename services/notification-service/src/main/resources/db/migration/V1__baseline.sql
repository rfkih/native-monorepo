-- notification-service baseline (#22) — the platform notification plane.
--
-- Tables:
--   * notification      — the aggregate (system of record for a notification to be delivered): the
--                         channel (EMAIL|PUSH), the recipient, the subject + body, and a status
--                         (PENDING|SENT|FAILED). Created via a TRIGGER event (ConsolidationClosed)
--                         and carrying the mandatory Auditable columns. A trigger_event_id (the
--                         consumed event's UUID) is UNIQUE so a re-delivered trigger can never create
--                         a second notification (the DB backstop behind ProcessedEventStore).
--   * delivery_receipt  — one row per delivery attempt of a notification: the outcome
--                         (DELIVERED|FAILED), the transport provider_ref, and when it was attempted.
--                         A delivery FAILURE is RECORDED here (a FAILED receipt), never swallowed.
--                         notification_id is UNIQUE: exactly one terminal receipt per notification in
--                         this slice (deliver-once), the backstop behind the idempotent consumer.
--   * outbox            — the transactional-outbox table the libs/events OutboxWriter writes to; the
--                         DeliveryReceipt event row commits atomically with the notification +
--                         delivery_receipt rows (rule 3).
--   * processed_event   — the idempotent-consumer dedupe store (libs/events ProcessedEventStore):
--                         one row per handled trigger event UUID, so a re-delivered
--                         ConsolidationClosed is handled at most once.
--
-- The app connects as a NON-superuser role (without BYPASSRLS), so RLS is genuinely enforced; FORCE
-- ROW LEVEL SECURITY makes the policy apply even to the table owner / migration role, so no
-- connection silently bypasses tenant isolation (CLAUDE.md rule 5 — defense in depth).
-- current_setting('app.current_tenant', true) returns NULL when the GUC is unset, so with no tenant
-- bound every policy predicate is false -> fail closed. The CONSUMER runs each handler inside a
-- TenantContext scope bound to the trigger event's company_id, so the WITH CHECK passes on insert.

-- ---------------------------------------------------------------------------
-- notification (the aggregate)
-- ---------------------------------------------------------------------------
CREATE TABLE notification (
    id              UUID         NOT NULL PRIMARY KEY,

    -- The delivery channel: EMAIL | PUSH. A check keeps the value space closed at the DB.
    channel         VARCHAR(16)  NOT NULL,

    -- The recipient address/token (an email address for EMAIL, a device token for PUSH). A
    -- placeholder recipient from config in this slice; the per-company recipient directory is a
    -- future integration. NOT PII in the sense of rule 6 (it is a notification destination, not a
    -- salary/NIK/bank account), but it is never logged at info level.
    recipient       VARCHAR(320) NOT NULL,

    -- The rendered message. Subject + body are server-composed, non-localized in this slice (the
    -- user-facing copy lives in the frontend i18n layer; this is the backend trigger summary).
    subject         VARCHAR(255) NOT NULL,
    body            TEXT         NOT NULL,

    -- The lifecycle status: PENDING on create, then SENT or FAILED after the delivery attempt.
    status          VARCHAR(16)  NOT NULL,

    -- The id of the TRIGGER event that created this notification (the consumed ConsolidationClosed's
    -- event UUID). UNIQUE: a second notification from a re-delivered trigger is impossible at the
    -- database level — the backstop behind the ProcessedEventStore dedupe (rule 3).
    trigger_event_id UUID        NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_notification_channel CHECK (channel IN ('EMAIL', 'PUSH')),
    CONSTRAINT ck_notification_status  CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT uq_notification_trigger_event UNIQUE (trigger_event_id)
);

ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification FORCE ROW LEVEL SECURITY;

CREATE POLICY notification_tenant_isolation ON notification
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- delivery_receipt (one terminal receipt per notification)
-- ---------------------------------------------------------------------------
CREATE TABLE delivery_receipt (
    id              UUID         NOT NULL PRIMARY KEY,

    -- The notification this receipt is for.
    notification_id UUID         NOT NULL,

    -- The delivery outcome: DELIVERED | FAILED.
    status          VARCHAR(16)  NOT NULL,

    -- The transport's reference for the attempt (a synthetic id for the stub), or NULL when the
    -- transport returned none.
    provider_ref    VARCHAR(255) NULL,

    -- When the delivery attempt completed (UTC).
    delivered_at    TIMESTAMPTZ  NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_delivery_receipt_status CHECK (status IN ('DELIVERED', 'FAILED')),
    -- Exactly one terminal receipt per notification in this slice (deliver-once); the backstop
    -- behind the idempotent consumer so a re-delivered trigger never writes a second receipt.
    CONSTRAINT uq_delivery_receipt_notification UNIQUE (notification_id)
);

ALTER TABLE delivery_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE delivery_receipt FORCE ROW LEVEL SECURITY;

CREATE POLICY delivery_receipt_tenant_isolation ON delivery_receipt
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The receipt is looked up / aggregated by notification within the bound tenant; index the path.
CREATE INDEX idx_delivery_receipt_notification ON delivery_receipt (notification_id);

-- ---------------------------------------------------------------------------
-- transactional outbox (consumed by Debezium / StubRelay)
-- ---------------------------------------------------------------------------
-- Columns match libs/events OutboxRecord / OutboxWriter exactly. published_at is set by the relay
-- (ignored by Debezium). NOT Auditable and NOT under RLS: it is infrastructure the relay tails, its
-- rows already carry company_id for downstream tenant scoping, and the writer always runs inside the
-- notification's RLS-scoped transaction. PII is NEVER written to a payload or header (rule 6).
CREATE TABLE outbox (
    id             UUID                     NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(255)             NOT NULL,
    aggregate_id   VARCHAR(255)             NOT NULL,
    event_type     VARCHAR(255)             NOT NULL,
    payload        BYTEA                    NOT NULL,
    headers        TEXT                     NULL,
    company_id     UUID                     NOT NULL,
    occurred_at    TIMESTAMPTZ              NOT NULL,
    published_at   TIMESTAMPTZ              NULL
);

-- The relay polls for UNPUBLISHED rows in occurrence order. A PARTIAL index (WHERE published_at IS
-- NULL) indexes only the rows the relay still has to ship, so it stays tiny.
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- processed_event (the idempotent-consumer dedupe store)
-- ---------------------------------------------------------------------------
-- libs/events ProcessedEventStore records each handled trigger event UUID here and skips duplicates
-- (INSERT ... ON CONFLICT DO NOTHING), so a re-delivered ConsolidationClosed is handled at most once
-- (rule 3). The claim runs INSIDE the same transaction as the notification + receipt + outbox
-- writes, so dedupe and side effects commit together. Not Auditable and not under RLS: keyed solely
-- by the globally-unique event id, not tenant-scoped data.
CREATE TABLE processed_event (
    event_id     UUID                     NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
