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
| `redis` | 6379 | Backs the gateway's distributed per-tenant token-bucket rate limit (M1.1) |

> **Port note:** Keycloak and a Spring service both default to 8080. Run the services on
> a different host port (e.g. `SERVER_PORT=8090`) or remap Keycloak when running both.

## Keycloak realm (M1.1 — gateway identity)

`keycloak/native-realm.json` is **auto-imported on boot** (the Keycloak service runs
`start-dev --import-realm` with `./keycloak` mounted at `/opt/keycloak/data/import`). It
provisions everything the gateway needs to validate tokens:

| Thing | Value | Why |
|---|---|---|
| Realm | `native` | the issuer (`http://localhost:8080/realms/native`) the gateway trusts |
| Client | `native-gateway` (confidential, secret `native-gateway-secret`) | the apps/BFF obtain RS256 access tokens through it; **Direct Access Grants** are on so tests can fetch a token via the password grant |
| Realm roles | `owner`, `manager`, `cashier` | projected into the token `roles` claim and onward as `X-Roles` |
| Sample user | `owner-acme` / `owner-password` | has the user attribute `company_id = 11111111-1111-1111-1111-111111111111` and roles `owner`, `manager` |
| Protocol mappers | `company_id` (user-attribute → claim), `roles` (realm-role → multivalued claim) | put **`company_id`** and **`roles`** into the **RS256 access token** the gateway reads |

The gateway validates the token's **signature, issuer, and expiry** against the realm's
JWKS (`/realms/native/protocol/openid-connect/certs`), then injects the validated
`company_id` / `sub` / roles downstream as the trusted `X-Company-Id` / `X-Actor` /
`X-Roles` headers (stripping any client-supplied copies first). A missing/invalid/expired
token is rejected at the edge with `401` and never reaches a service.

Fetch a token by hand (mirrors what the gateway integration tests do):

```bash
curl -fsS -X POST \
  http://localhost:8080/realms/native/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=native-gateway \
  -d client_secret=native-gateway-secret \
  -d username=owner-acme \
  -d password=owner-password | jq -r .access_token
```

> The realm file is duplicated at `services/gateway/src/test/resources/native-realm.json`
> so the gateway's Testcontainers-Keycloak imports the **same** realm the dev stack does.
> Keep the two in sync when you change the realm.

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
