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
| `schema-registry` | 8081 | Avro Schema Registry (governance/tooling; the hot path uses raw Avro bytes, base64-encoded on the wire — no Confluent serde) |
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

# 3) Register the Debezium outbox connectors — one per producing service DB.
#    restaurant = outbox-connector.json; org/employee/carwash/entitlement have their
#    own copies. The entitlement connector is REQUIRED for any carwash POS work:
#    without it EntitlementGranted never reaches carwash's projection and the gate
#    403s not-entitled forever.
for f in outbox-connector org-outbox-connector employee-outbox-connector \
         carwash-outbox-connector entitlement-outbox-connector; do
  curl -fsS -X POST http://localhost:8083/connectors \
    -H 'Content-Type: application/json' \
    -d @docker/debezium/$f.json
done

# 4) Confirm they are RUNNING.
curl -fsS http://localhost:8083/connectors/restaurant-outbox-connector/status
curl -fsS http://localhost:8083/connectors/carwash-outbox-connector/status
curl -fsS http://localhost:8083/connectors/entitlement-outbox-connector/status
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
| `payload` (bytea) | the message **value**, **base64-encoded on the wire** | Avro bytes shipped as base64 text — Debezium decodes the `bytea` payload as a `ByteBuffer` that `ByteArrayConverter` cannot ship, so the connector sets `binary.handling.mode=base64` and a `StringConverter`; the value is still the Avro bytes, no Confluent serde |
| `id` | a Kafka **header** `id` | the durable **event id** the consumer dedupes on (not the offset) |
| `company_id` | a Kafka **header** `company_id` | the owning tenant |
| `traceparent` | a Kafka **header** `traceparent` | W3C trace-context header (ADR 0010 #13 — outbox→Kafka trace continuity); absent when the column is NULL (no span active) — consumers restore the trace context so their listener span is a child of the producer span; a missing header starts a new root span |
| `occurred_at` | the record timestamp | |

The value on the wire is the **base64-encoded Avro bytes** (a transport encoding, not a
re-serialization): Debezium decodes the `bytea` `payload` as a `java.nio.ByteBuffer`, which
Connect's `ByteArrayConverter` rejects (`ByteArrayConverter is not compatible with objects
of type java.nio.HeapByteBuffer`) — so the connector emits base64 text via
`binary.handling.mode=base64` + a `StringConverter`. The services then base64-decode the
value back to the **raw Avro bytes** with `libs/events Base64ByteArrayDeserializer` and
decode those bytes via `libs/events AvroSerde` against their own copy of the schema — still
**not** a Confluent Schema Registry serde, and `AvroSerde`'s raw-bytes contract is unchanged.
The same base64 transport is used by the test producers, the `StubRelay`, and the DLT
re-publisher, so the wire format is consistent end to end. The Schema Registry container is
present for governance and future producers, not the consume hot path.

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

---

## Observability stack (scorecard #13)

**Config-only / author-only** — the same status as the rest of this directory. Not exercised
against a live multi-service run; treat it as the target config for a local dev observability
session.

### Components

| Container | Host port | Purpose |
|---|---|---|
| `prometheus` | 9090 | Scrapes `/actuator/prometheus` on all 8 services every 15 s |
| `tempo` | 3200 (query), 4317 (gRPC), 4318 (HTTP) | OTLP trace backend; receives spans from services |
| `grafana` | 3000 | Dashboards; creds `admin` / `admin`; anonymous viewer also enabled |

Provisioned datasources: **Prometheus** (default) + **Tempo** (with trace-to-metrics
correlation back to Prometheus). Provisioned dashboards:

| File | Dashboard | What it shows |
|---|---|---|
| `grafana/dashboards/native-red.json` | Native — RED | Rate, Errors, Duration from `http_server_requests_seconds` (templated by service) |
| `grafana/dashboards/native-events.json` | Native — Events | Kafka consumer lag (`kafka_consumer_fetch_manager_records_lag`), listener throughput/latency (`spring_kafka_listener_seconds_*`), outbox lag (`native_outbox_unpublished` — added by the outbox-metrics work stream) |
| `grafana/dashboards/native-jvm.json` | Native — JVM / Process | Heap, non-heap, GC pause, CPU, threads, buffer pools |

### Bring up the observability overlay

Compose with the dev stack (the typical case):

```bash
docker compose -f docker/compose.dev.yml -f docker/compose.observability.yml up -d
```

Or stand up the observability containers alone (no Postgres/Kafka/Keycloak):

```bash
docker compose -f docker/compose.observability.yml up -d
```

Open Grafana at http://localhost:9090 (Prometheus) and http://localhost:3000 (Grafana).

### Scrape targets — port placeholders

Services run on the host (not containerised); Prometheus reaches them via
`host.docker.internal`. The ports in `docker/prometheus/prometheus.yml` are **suggested
defaults** — the operator must start each service with the matching `SERVER_PORT`:

| Service | Suggested `SERVER_PORT` |
|---|---|
| gateway | 8090 |
| org-service | 8091 |
| restaurant-service | 8092 |
| carwash-service | 8093 |
| employee-service | 8094 |
| finance-service | 8095 |
| entitlement-service | 8096 |
| notification-service | 8097 |

Edit `docker/prometheus/prometheus.yml` targets to match your actual ports, then reload
Prometheus without restarting:

```bash
curl -X POST http://localhost:9090/-/reload
```

### Enable OTLP span export (ADR 0010)

OTLP export is **OFF by default** (ADR 0010 `ObservabilityEnvironmentPostProcessor` sets
`management.tracing.export.enabled=false` so no collector is contacted by default — the
SDK is real and trace IDs populate the MDC, but nothing is shipped). When this overlay is
running, opt a service in by setting environment variables at startup:

```bash
MANAGEMENT_OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces \
MANAGEMENT_TRACING_ENABLED=true \
MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true \
  ./gradlew :services:<svc>:bootRun
```

Tempo listens on:
- `localhost:4318` — HTTP/protobuf (recommended; no gRPC stubs needed in the JVM)
- `localhost:4317` — gRPC

After spans arrive, open Grafana Explore → Tempo datasource to search by trace ID or
service name. The Tempo datasource is wired with trace-to-metrics correlation: clicking a
span opens the native-red dashboard pre-filtered to that service and time window.

### Tear down the overlay

```bash
# Keep volumes (metric / trace data persists for the next session):
docker compose -f docker/compose.dev.yml -f docker/compose.observability.yml down

# Also wipe metric / trace data:
docker compose -f docker/compose.dev.yml -f docker/compose.observability.yml down -v
```
