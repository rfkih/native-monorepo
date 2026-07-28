# RUNBOOK — run, test, debug locally (+ the gotchas)

> **For an AI agent:** the validation slice has been run end-to-end against the live stack. The exact
> commands + the non-obvious gotchas are here so you don't re-derive them (each one cost real
> debugging). See PROJECT-MAP for the module map, DEVLOG for history/status.

## Build & test
```bash
./gradlew build          # the FULL gate: compile + unit + Testcontainers integration + ArchUnit
                         # + spotless (google-java-format) + checkstyle + jacoco coverage. One command.
./gradlew :services:finance-service:build     # one module (faster; finance is the slowest, ~1–3 min)
./gradlew spotlessApply  # auto-format before building if checkstyle/spotless complain
```
- Tests use **Testcontainers** (real Postgres/Kafka) — a Docker daemon must be running. RLS + `ON CONFLICT`
  are exercised against real Postgres as the non-superuser `app_user` role.
- The integration tests **stub the Debezium relay** (StubRelay) — so they do NOT cover the real
  outbox→Kafka CDC path. That path is only proven by the live run below (which is why it caught 3 bugs).

## Run the dev stack + the services locally
```bash
# 1) infra (Postgres-per-service, Kafka, Debezium Connect, Keycloak, Redis):
docker compose -f docker/compose.dev.yml up -d --wait
#    On first boot Postgres runs docker/postgres/init/01-init-databases.sql → 7 DBs + non-superuser roles.

# 2) run a service (they are NOT containerized in the dev stack — run the bootJar on the host):
./gradlew :services:<svc>:bootJar
JAVA25="$HOME/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2/bin/java.exe"   # see gotcha #4
DB_URL=jdbc:postgresql://localhost:5432/<svc>_service DB_USERNAME=<svc>_service DB_PASSWORD=<svc>_service \
SPRING_PROFILES_ACTIVE=dev SERVER_PORT=80NN KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
NATIVE_DEV_TENANT_FILTER_ENABLED=true \
  "$JAVA25" -jar services/<svc>/build/libs/<svc>-0.1.0-SNAPSHOT.jar

# 3) register the Debezium outbox connectors (after the producing service has migrated its outbox
#    table) — ONE PER SERVICE DB: outbox-connector.json (restaurant), org-outbox-connector.json,
#    employee-outbox-connector.json (payroll -> finance labor cost NEVER flows without it):
curl -fsS -X POST http://localhost:8083/connectors -H 'Content-Type: application/json' \
  -d @docker/debezium/outbox-connector.json
curl -fsS http://localhost:8083/connectors/restaurant-outbox-connector/status   # task must be RUNNING
```
Drive the loop: `POST /api/v1/sales` (headers `X-Company-Id: <uuid>`, `X-Actor: x`, `X-Roles: cashier`,
body `{businessId,amountMinor,currency,idempotencyKey}`) → then `GET /api/v1/revenue?period=YYYY-MM` on
finance (same `X-Company-Id`) shows the consolidated revenue move.

Local port convention (dev recipe): org `8082`, employee `8084`, finance `8085`, restaurant `8086`,
gateway `8090`. employee-service is gateway-routed via `EMPLOYEE_SERVICE_URI` (e.g.
`http://localhost:8084`); the Vite dev proxy reaches it via `VITE_EMPLOYEE_URL` in the header-trust
recipe. The console HR/payroll surfaces (org-unit hub → Employees/Payroll tabs) need it running.

**Adding a realm role to a RUNNING Keycloak** (realm-JSON edits do NOT auto-apply, and a re-import
requires dropping the KC db — which WIPES users): create it via the admin API instead, e.g. the
`employee` role:
```bash
TOKEN=$(curl -s -X POST http://localhost:18080/realms/master/protocol/openid-connect/token \
  -d 'grant_type=password&client_id=admin-cli&username=admin&password=admin' | jq -r .access_token)
curl -s -X POST http://localhost:18080/admin/realms/native/roles \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"employee","description":"Self-service employee — /me surface only"}'
```

## GOTCHAS (each cost real debugging — read before running locally)
1. **Host `DB_*` env vars override the service defaults.** The yml uses `${DB_PASSWORD:default}`; if the
   shell has `DB_PASSWORD`/`DB_USERNAME`/`DB_URL` set (e.g. another project), Spring picks the host value
   → `FATAL: password authentication failed`. **Always pass `DB_URL/DB_USERNAME/DB_PASSWORD` explicitly**
   per service when running locally.
2. **The dev tenant filter is OFF by default.** `DevTenantFilter` is `@ConditionalOnProperty(
   native.dev-tenant-filter.enabled=true)` (a safety gate — it trusts headers). Without
   `NATIVE_DEV_TENANT_FILTER_ENABLED=true`, a tenant-scoped request fails 500 "No tenant bound". The
   header names are `X-Company-Id` (must be a UUID) + `X-Actor` (both required, else 400).
3. **The Debezium connector needed 3 fixes for the real outbox path** (all now in
   `docker/debezium/outbox-connector.json`; the Testcontainers tests could not catch these because they
   stub the relay): (a) `publication.autocreate.mode: filtered` — the connector runs as a non-superuser
   role, and `FOR ALL TABLES` (the default) needs superuser; (b) NO `event.timestamp` mapping — `occurred_at`
   is a timestamp, not the INT64 the EventRouter wants; (c) `binary.handling.mode: base64` + a per-connector
   `value.converter=StringConverter` — Debezium decodes the `bytea` payload as a `ByteBuffer` that
   `ByteArrayConverter` rejects, so the payload is base64'd on the wire and the consumer's
   `libs/events Base64ByteArrayDeserializer` decodes it back to the raw Avro bytes (AvroSerde unchanged).
4. **System `java` is 21; the bootJars are Java 25.** Run with the foojay-provisioned JDK 25 at
   `~/.gradle/jdks/eclipse_adoptium-25-*/bin/java` (or `./gradlew :svc:bootRun`, which uses the toolchain).
5. **A raw `psql` connection sees NO rows from RLS tables** unless it sets `app.current_tenant`
   (`SET app.current_tenant = '<company-uuid>'`) — this is RLS working, not missing data. Query via the
   service API (which binds the tenant) or set the GUC. Use the `postgres` superuser only for admin.
6. **Slot drop ⇒ stale Connect offset.** If you drop a replication slot and re-register the same-named
   connector, Connect's stored offset is stale → it skips the snapshot / streams from the wrong LSN.
   Register a FRESH connector (unique name + `slot.name` + `topic.prefix`) for a clean run.
7. **Port conflicts** (another local stack may hold 5432/9092/8081). The compose publishes those host
   ports; remap if needed (Kafka host port + `KAFKA_ADVERTISED_LISTENERS` EXTERNAL must change together;
   the in-cluster `kafka:29092` listener is unaffected). Schema-registry (8081) is optional — the consume
   path uses raw Avro bytes, not the registry.
8. **Own-sales commission needs a UUID actor — so test it over OIDC, not header-trust.** A sale emits
   its `sales_amount`@`employee` metric keyed by `subject_id = X-Actor` (the JWT `sub`), and
   `metric_input.subject_id` is a UUID column. The header-trust recipe's fixed `X-Actor` (e.g.
   `owner@console.dev`) is NOT a UUID, so `SaleWriter` **skips** the metric emission (logged at debug;
   the sale itself still records). Commission therefore accrues zero in header-trust mode. For the
   end-to-end commission story, run the OIDC recipe (real Keycloak logins carry a UUID `sub`) and ring
   the sales as the linked employee login. The employee's `employee.user_id` (V7 link) must equal that
   login's `sub` for the payroll run to match the metric rows.

## 2026-07 org-tree flattening (ADR 0012) — dev data

The org tree lost its BRANCH level and every new business unit seeds a default outlet; two
migration-file comments also changed, which breaks **Flyway checksum validation** on databases
that already ran them.

- **Primary path — full stack reset** (required if a service fails `Flyway validate` at boot):
  `docker compose -f docker/compose.dev.yml down -v` then bring the stack back up and re-signup.
  Keycloak users survive if KC uses the persistent `keycloak` DB volume — only re-seed companies.
- **Keep-data alternative** (pre-ADR tenants with zero outlets): per business unit, create the
  outlet through the API so the OrgUnitCreated event flows through the outbox and downstream
  ref tables stay consistent — `POST /api/v1/org-units {"name":"<name>","type":"outlet",
  "parentId":"<business-unit-id>"}` as that tenant — then re-create menu/tables under the outlet
  via the console. Historical restaurant/finance rows keyed to the business-unit id remain
  (visible as a business-unit-named row in outlet P&L) — cosmetic, pre-GA.
- The console POS/Menu/Kitchen now BLOCK on an "outlet gate" until the tenant has an active
  outlet the signed-in user may ring on; companies created after ADR 0012 always have one.

## Tear down
```bash
docker compose -f docker/compose.dev.yml down       # keep the Postgres volume (data persists)
docker compose -f docker/compose.dev.yml down -v     # also drop data + replication slots
```

## Troubleshooting (symptom → cause → fix)
| symptom | cause | fix |
|---|---|---|
| service exits 0 at boot, `password authentication failed` | host `DB_*` env leak (gotcha #1) | pass DB creds explicitly |
| `No tenant bound in the current scope` (500) | dev tenant filter not enabled (gotcha #2) | `NATIVE_DEV_TENANT_FILTER_ENABLED=true` + the `X-Company-Id`/`X-Actor` headers |
| `Unsupported class file major version 69` | running a Java 25 jar with Java 21 (gotcha #4) | use the JDK-25 path |
| Debezium task FAILED `must be superuser…FOR ALL TABLES` | (gotcha #3a) | `publication.autocreate.mode: filtered` |
| Debezium task FAILED `'occurred_at' is not of type INT64` | (gotcha #3b) | remove `event.timestamp` mapping |
| Debezium task FAILED `ByteArrayConverter…HeapByteBuffer` | (gotcha #3c) | `binary.handling.mode: base64` + StringConverter |
| message on Kafka but consumer silent / topic empty | stale Connect offset (gotcha #6) | fresh connector name/slot/prefix |
| `psql` returns no rows from a known-populated table | RLS, no tenant GUC (gotcha #5) | set `app.current_tenant` or query the API |
