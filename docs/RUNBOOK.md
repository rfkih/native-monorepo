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
#    table) — ONE PER SERVICE DB. Register ALL of them, not a hand-picked subset: a producer whose
#    connector is missing writes outbox rows that reach nobody, and NO health check catches it
#    (recover_cdc / ops-watch only assert that ALREADY-REGISTERED connectors have RUNNING tasks —
#    that is exactly how finance-service shipped with no connector at all, see DEVLOG 2026-09-02).
for f in docker/debezium/*.json; do
  name=$(jq -r '.name' "$f")
  jq '.config' "$f" | curl -fsS -X PUT -H 'Content-Type: application/json' \
    --data-binary @- "http://localhost:8083/connectors/$name/config" >/dev/null
done
# every task must be RUNNING, and the count must match the number of files:
curl -fsS http://localhost:8083/connectors | jq 'length'   # expect: 9
for c in $(curl -fsS http://localhost:8083/connectors | jq -r '.[]'); do
  echo "$c $(curl -fsS "http://localhost:8083/connectors/$c/status" | jq -r '.tasks[].state')"
done
```
> **One-command restart:** `.\scripts\start-dev-services.ps1 [-JarRoot C:\some-worktree] [-Only a,b]`
> checks all eight host-service ports, launches only what's dead (detached; logs in
> `%TEMP%\native-services\`), and waits for health. This is the recovery for "reboot/closed terminal
> killed my services → the console shows bodyless 500s/502s". Jars must already be bootJar-built —
> when the main checkout's build-logic is poisoned (gotcha above), build in a worktree and pass it
> as `-JarRoot`.

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

**Multi-company claim mapper on a RUNNING Keycloak** (multi-company ownership, ADR 0021): the
`company_id` protocol mapper must be `multivalued: true` on BOTH the `native-gateway` and
`native-console` clients (the realm JSON now ships it, but a live KC keeps its old mapper — same
no-auto-apply rule as above). Update each client's mapper via the admin API (find the client id +
mapper id, then PUT the mapper with `"multivalued": "true"` added to its config), or — if losing
users is acceptable — drop the KC db and re-import. Symptom of a stale scalar mapper: a login with
2+ companies gets a malformed `company_id` claim and the gateway 403s every request.

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

## Offline-mode manual test (airplane-mode script, ADR 0028)

1. Run the stack + console, sign in, open a POS (any vertical), let the catalog load once while
   online (this primes the IndexedDB catalog + effective-rules cache).
2. DevTools → Network → set **Offline** (or kill the gateway). The offline banner appears; the
   POS keeps selling: **cash only**, coupon/points/gift-card inputs disabled, totals labeled
   *provisional*.
3. Ring 2–3 cash sales. Each lands in the queue (badge on the POS header); receipts are marked
   provisional. Refresh the tab while offline — the queue must survive (persisted storage).
4. Go back online. The queue replays serially (watch the sync center): rows go SYNCED; re-running
   a replay (or a second tab) must never double-post — the server's idempotency key returns the
   existing sale (409 → treated as synced).
5. Verify in finance: each replayed sale posts into the **sale-day** period (`clientOccurredAt`),
   not the sync time; dashboard revenue matches the drawer.
6. Mismatch drill: while offline, change the tax rule (via API as owner), sell, reconnect — the
   row lands SYNCED_WITH_MISMATCH with both totals in the end-of-day report.
7. Rejection drill: queue a sale, keep the tab closed >48h (or fake `clientOccurredAt`), replay →
   422 REJECTED, kept visible in the sync center; nothing silently re-dated.

## Closing kasir + platform settlements manual drill (ADR 0036)

Header-trust curls direct to restaurant :8086 / finance :8085 (`X-Company-Id: <uuid>`,
`X-Actor: drill`, `X-Roles: owner`); through the gateway use OIDC + `Idempotency-Key` the same way.

1. **Open the drawer**: `POST /api/v1/register-sessions` `{businessId, openingFloatMinor,
   currency}` + a fresh `Idempotency-Key` → 201. Same key + same body replays 200; same key +
   different body → 409 `register-session-idempotency-key-conflict`; a second open at the outlet →
   409 `register-session-already-open`.
2. Ring CASH sales (order checkout), sell a gift card for CASH, ring a gift-card+cash split sale,
   partially refund a cash payment (`POST /api/v1/payments/{id}/refund`).
3. **Close**: `POST /api/v1/register-sessions/{id}/close` `{countedCashMinor}` + key `close:<id>`.
   Verify `cashSalesMinor` = Σ cash-collected sale portions **+ cash gift-card sales**,
   `cashRefundsMinor` = Σ `payment_refund` deltas in the window, `expectedCashMinor = float +
   sales − refunds`, `overShortMinor = counted − expected` (SIGNED). Missing `countedCashMinor` →
   400 with a field error, never a silent 0.
4. Verify the finance variance JE (psql superuser, RUNBOOK gotcha 5): short → `Dr 5700 / Cr 1900`;
   over → `Dr 1900 / Cr 4300`; zero → processed, no entry.
5. **ONLINE sale** (Phase B): checkout with `payment.tenderType=ONLINE` + `channelCode` (channel
   must exist + be active via `/api/v1/sales-channels`) → finance JE debits **1250** (not 1900) and
   `platform_receivable` accrues the gross under the channel. ONLINE + gift-card/loyalty-redeem →
   400. ONLINE cash never enters the register-close window (not CASH tender).
6. **Platform settlement** (Phase C): `GET /api/v1/platform-settlements/outstanding`, then
   `POST /api/v1/platform-settlements` `{channelCode, grossMinor, netMinor, currency}` +
   `Idempotency-Key` → 201 posting `Dr 1900 (net) + Dr 5710 (fee) / Cr 1250 (gross)` and
   decrementing the accumulator. Same-key replay 200; gross > outstanding → 422
   `platform-settlement-over-settlement`; net > gross → 422; the payout's bank-statement line then
   reconciles via the ADR-0016 CLEARING sweep.

NOTE: loyalty-service is NOT in `scripts/start-dev-services.ps1` — the gift-card mirror
(`GiftCardSold` → loyalty → `GiftCardStateChanged` → restaurant `gift_card_ref`) is inert without
it. Launch its jar manually (any free port, e.g. 8093) with the same dev env vars the script sets.

## QRIS modes + Midtrans gateway drill (ADR 0045)

payment-service (:8091, DB `payment_service`) owns QRIS modes + PSP charges. On an EXISTING dev
stack the Postgres init won't re-run — create the role/DB manually once
(`CREATE ROLE payment_service LOGIN PASSWORD 'payment_service' REPLICATION; CREATE DATABASE
payment_service OWNER payment_service;` + `GRANT ALL ON SCHEMA public TO payment_service` inside
it), or `down -v`. Register the Debezium connector like every other service:
`curl -X POST -H "Content-Type: application/json" --data @docker/debezium/payment-outbox-connector.json localhost:18083/connectors`.

Env: `NATIVE_PII_KEY` (credential encryption; dev default committed),
`NATIVE_PAYMENT_WEBHOOK_BASE_URL` = the PUBLIC gateway origin Midtrans must reach (the UAT Funnel
URL; blank in local dev → the per-charge callback header is omitted and settlement arrives via
`/sync`).

**STATIC drill** (no PSP at all): owner PUT `/api/v1/payment-settings` `{mode: "STATIC"}` → POST
`.../static-qr` (multipart `file`, jpeg/png/webp ≤ 2 MiB) → the till's QRIS pending panel and the
customer display show the image; capture stays "Mark as paid" (Tandai lunas).

**GATEWAY drill** (Midtrans SANDBOX, merchant's own account):
1. Owner: PUT `/api/v1/payment-settings` `{mode:"GATEWAY", provider:"MIDTRANS",
   environment:"SANDBOX", serverKey:"SB-Mid-server-…"}` (write-only — reads return `last4` only;
   ciphertext at rest, verify via psql: `server_key_encrypted` is bytes, never the key).
2. Till: QRIS checkout creates the PENDING payment as usual, then
   `POST /api/v1/payment-charges` (`Idempotency-Key: charge:<paymentId>:1`) → 201 with
   `qrString` — scan with the Midtrans sandbox simulator. Poll `GET /payment-charges/{id}`.
3. Settlement path A (webhook): Midtrans calls
   `POST /api/v1/psp-webhooks/midtrans/{companyId}` (per-charge `X-Override-Notification` — needs
   the public URL). Path B (no tunnel): `POST /payment-charges/{id}/sync` applies the same
   transition. Either way: charge → SUCCEEDED + ONE `PaymentChargeSucceeded` outbox row → the
   vertical consumer runs its EXISTING capture → sale + `SaleRecorded` → finance debits 1901.
4. Negative drills: cancel-vs-paid race (`/cancel` when the sandbox already settled → SUCCEEDED,
   capture proceeds — money is never swallowed); tampered `signature_key` → uniform 401; unknown
   `order_id` / wrong `gross_amount` / settlement AFTER a local cancel → parked in `error_log`
   (sources `payment.psp-webhook.*`), answered 200, NO capture.
5. Bank payout: reconcile the (net-of-MDR) deposit with category `QRIS_CLEARING` + `feeMinor` →
   `Dr BANK (net) + Dr 5720 (fee) / Cr 1901 (gross)` (finance V52).

**OPS — parked webhook anomalies** (`error_log`, `source LIKE 'payment.psp-webhook.%'`): these are
"money moved at the PSP with no local capture" cases. `late-settlement` after a cashier cancel =
refund the customer from the MERCHANT's own Midtrans dashboard (Native never holds funds);
`amount-mismatch`/`unknown-order` = investigate before any manual capture. Never replay these
blind.

## Object store (MinIO) — media drill + operations (ADR 0048)

The dev stack's MinIO holds all binary media: menu images (`restaurant/…`, the only
anonymous-readable prefix), expense receipts (`employee/…`) and static QRIS (`payment/…`),
content-addressed as `{service}/{companyId}/{domain}/{sha256}.{ext}` in bucket `native-media`.
`minio-init` (docker/minio/init.sh) provisions the bucket + versioning + one prefix-scoped user
per service on every stack start — idempotent, safe to re-run. The community image has NO web
console: administer with `mc` one-liners, e.g.

```
docker run --rm --network native-dev_default minio/mc:RELEASE.2025-08-13T08-35-41Z \
  sh -c "mc alias set n http://minio:9000 minioadmin minioadmin && mc ls -r n/native-media"
```

- **Round-trip drill**: console → Products → add item with a photo → the response `imageUrl`
  is `http://localhost:8090/api/media/restaurant/<tenant>/menu/<sha>.jpg` and renders anonymously
  (curl it with no token: 200 + `Cache-Control: … immutable`). A bogus key under `restaurant/`
  is **404** on MinIO (a strict AWS-style backend would 403 — the anonymous policy is
  GetObject-only, no ListBucket; browsers treat both alike); ANY key under `employee/` or
  `payment/` is 401/403 (route + policy both deny — by design). Anonymous listing of the bucket
  or any prefix is always denied (verified 403 — the whole point of the explicit policy).
- **Per-tenant menu-image backfill** (legacy inline base64 → store): as an OWNER of that tenant,
  `curl -X POST -H "Authorization: Bearer $TOK" $BASE/api/v1/menu/images/migrate` → `{"migrated":N,
  "skipped":M}`. Idempotent; run once per tenant after deploy. Receipts + QRIS need nothing:
  they read-through migrate on first serve (`native.media.read-through-migrate`, default on).
- **Backup (REQUIRED before prod trusts receipts)**: objects do NOT ride along in `pg_dump`.
  Versioning is on; mirror with
  `mc mirror --overwrite n/native-media /backup/native-media` (or a second S3 target) on a
  schedule. Restore = mirror back + restart nothing (keys are content-addressed).
- **Troubleshooting**: images 404 after a redeploy → did `minio-init` exit 0? (`docker logs
  native[-uat]-minio-init`). Menu images broken but receipts fine → the anonymous policy on
  `restaurant/` is missing (re-run minio-init) or the gateway `MEDIA_URI` is wrong. Upload 500s →
  the service's `MEDIA_SECRET_KEY` doesn't match what minio-init provisioned (it UPDATES the
  user's secret on every run — restart the stack so both sides agree).

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

---

## Production operations (VPS `middleware` — ADR 0053/0057)

Prod lives on the owner VPS at `~/native-prod` (user `starsky`, 202.74.75.3 / tailnet 100.112.13.126),
co-located with an unrelated live workload — never touch the `vector-*`/blackheart containers.
Public edge = two Cloudflare **quick tunnels** (EPHEMERAL URLs; current values in `~/native-prod/prod.env`).

| Task | How |
|---|---|
| **Release to prod** | Merge to master (green `gate`+`ai-gate`) → `git tag -a vX.Y.Z && git push origin vX.Y.Z` → approve the `deploy-prod` run (production environment). Health-gated, auto-rolls back to `LAST_GOOD` on failure. |
| **Roll back** | `ssh <vps> 'cd ~/native-prod && bash scripts/prod-rollback.sh'` (LAST_GOOD) or `... prod-rollback.sh vX.Y.Z` for any retained release. |
| **Tunnels died / new URLs** | `ssh <vps> 'cd ~/native-prod && bash scripts/prod-bootstrap.sh $(cat LAST_GOOD)'` — re-discovers URLs, rewrites prod.env, re-patches Keycloak. |
| **Backups** | Nightly cron 02:10 WIB → `backups/nightly/*.enc` (AES-256; 11 DBs + MinIO + prod.env; keep 14). Offsite: Windows task `NativeProdBackupPull` pulls to `%USERPROFILE%\native-prod-backups` daily 04:00 (keep 30). Passphrase: `BACKUP_PASSPHRASE` in prod.env + `%USERPROFILE%\.native-prod-backup-passphrase.txt` — ALSO keep it in a password manager. |
| **Restore drill (monthly)** | `ssh <vps> 'bash ~/native-prod/scripts/prod-restore-drill.sh'` — decrypts the newest archive, restores finance_service into a throwaway postgres, asserts the schema. |
| **Watchdog** | `ops-watch.yml` once a day at 20:00 UTC (03:00 WIB, just after the 19:10 UTC nightly backup): disk (fail >82%, warn >70%), container health, Debezium connector **tasks** RUNNING, backup freshness (<26 h), external tunnel probes. A failed run = GitHub notification. Detection latency is up to 24 h by design — run it on demand after a deploy or a suspected outage: `gh workflow run ops-watch.yml`. |
| **Manual deploy (no CI)** | `ssh <vps> 'cd ~/native-prod && bash scripts/prod-deploy.sh vX.Y.Z'` — requires `releases/vX.Y.Z.images.yml` (digest-pinned) present. |
| **Android app (prod)** | Served at `https://<prod-origin>/native-app-latest.apk` + `/native-employee-app-latest.apk` from the `docker/prod/downloads` edge mount (ADR 0058). Prod is a SEPARATE app from UAT (`id.co.nativeapp.till` "Native" vs `…​.till.uat` "Native UAT", amber badge). **DEFERRED until a stable domain**: the origin is baked into the APK, so build (`npm run build:prod` in `frontend/native-till`) only against the named tunnel/domain, not an ephemeral quick-tunnel URL — the wrapper refuses the latter. Until then the prod link 404s by design. |

Gotchas: the edge has **no host port** (probe via `docker exec native-prod-edge wget ...`); the tunnel
containers ride compose profiles — scripts export `COMPOSE_PROFILES` from prod.env, so never run bare
`docker compose up --remove-orphans` by hand (it would delete the tunnels); prod.env is generated,
600, NEVER synced or committed — losing it strands the PII ciphertexts (it IS in every encrypted backup).
Disk is the binding constraint (~82% after each release leaves two image sets) — prune superseded
`ghcr.io/rfkih/native-monorepo` images (`docker image prune` won't catch digest-pulled ones) and plan expansion.
