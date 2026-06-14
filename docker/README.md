# Native — local dev stack (M0.4)

The real transport loop for local development:

```
restaurant-service ──(outbox INSERT)──▶ Postgres `outbox` table
        Debezium / Kafka Connect ──(outbox event router)──▶ Kafka topic `SaleRecorded`
                finance-service @KafkaListener ──▶ ledger_posting + consolidated_revenue
```

Everything here is **config** (author-only): it is the genuine event transport behind
the validation slice, not part of the Gradle build or tests. The automated tests stand
up their own ephemeral Postgres + Kafka via Testcontainers; this stack is for running
the services together by hand.

## Components

| Service | Port | Purpose |
|---|---|---|
| `postgres` | 5432 | PostgreSQL 16, **one database + app role per service**, `wal_level=logical` for CDC |
| `kafka` | 9092 | Kafka (KRaft, no ZooKeeper) — the event backbone, one topic per `event_type` |
| `schema-registry` | 8081 | Avro Schema Registry (governance/tooling; the hot path uses raw Avro bytes) |
| `connect` | 8083 | Debezium / Kafka Connect — tails each `outbox` table via the outbox event router |
| `keycloak` | 8080 | OIDC / RS256 JWT issuer (`company_id` + roles), validated by the gateway (M1.1) |

> **Port note:** Keycloak and a Spring service both default to 8080. Run the services on
> a different host port (e.g. `SERVER_PORT=8090`) or remap Keycloak when running both.

## Bring it up

```bash
# 1) Start the infrastructure.
docker compose -f docker/compose.dev.yml up -d

# 2) Wait until Connect is healthy (it depends on Kafka + Postgres being up).
curl -fsS http://localhost:8083/connectors

# 3) Register the Debezium outbox connector for restaurant-service.
curl -fsS -X POST http://localhost:8083/connectors \
  -H 'Content-Type: application/json' \
  -d @docker/debezium/outbox-connector.json

# 4) Confirm it is RUNNING.
curl -fsS http://localhost:8083/connectors/restaurant-outbox-connector/status
```

A connector is registered **per service database** (DB-per-service). To add
finance/org/other producers, copy `debezium/outbox-connector.json`, point
`database.*`, `slot.name`, `publication.name`, and `topic.prefix` at that service's DB,
and POST it the same way.

## How the outbox router maps a row to an event

The [outbox event router](https://debezium.io/documentation/reference/transformations/outbox-event-router.html)
turns each `outbox` row into one Kafka record:

| Outbox column | Becomes | Notes |
|---|---|---|
| `event_type` | the **topic name** (e.g. `SaleRecorded`) | `route.by.field` → `${routedByValue}` |
| `aggregate_id` | the message **key** (partitioning) | e.g. the `sale_id` |
| `payload` (bytea) | the message **value**, shipped **verbatim** | raw Avro bytes; `ByteArrayConverter`, no re-encode |
| `id` | a Kafka **header** `id` | the durable **event id** the consumer dedupes on (not the offset) |
| `company_id` | a Kafka **header** `company_id` | the owning tenant |
| `occurred_at` | the record timestamp | |

The services consume the value as **raw Avro bytes** via `libs/events AvroSerde` against
their own copy of the schema — **not** a Confluent Schema Registry serde — consistent with
how the outbox stores events. The Schema Registry container is present for governance and
future producers, not the consume hot path.

## Run the loop end to end

1. Start the stack and register the connector (above).
2. Run org-service, restaurant-service, and finance-service against this Postgres/Kafka
   (their `application.yml` defaults already point at `localhost:5432` / `localhost:9092`;
   each connects as its own app role).
3. `POST` a sale to restaurant-service → it writes a `SaleRecorded` outbox row.
4. Debezium ships it to the `SaleRecorded` topic → finance-service consumes it, posts to
   the ledger, and the consolidated-revenue read model moves.
5. `GET /api/v1/revenue?period=YYYY-MM` on finance-service shows the tenant's revenue.

## Tear down

```bash
docker compose -f docker/compose.dev.yml down          # keep the Postgres volume
docker compose -f docker/compose.dev.yml down -v        # also drop data + replication slots
```
