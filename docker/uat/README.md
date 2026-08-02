# Native UAT stack — public via cloudflared quick tunnel

The full platform (10 services + console + own infra) containerized on one machine and
exposed to the internet through a **cloudflared quick tunnel** — a random
`https://xxx.trycloudflare.com` URL, no Cloudflare account or domain needed.

```
Internet (testers) ──https──> xxx.trycloudflare.com   (Cloudflare edge, TLS)
                                   │ outbound-only tunnel
                              cloudflared ──> edge (nginx, uat/edge.conf)
        /              -> console      (SPA; runtime env.js config)
        /auth/callback -> console      (OIDC redirect carve-out)
        /auth/         -> keycloak     (KC_HTTP_RELATIVE_PATH=/auth)
        /api/          -> gateway ──> org/restaurant/…/employee (:8080 each)
        /auth/admin, /auth/realms/master -> 404 (blocked)
```

One public origin ⇒ no CORS (the gateway deliberately has none). Services validate the
token issuer against the **public** URL but fetch JWKS **internally** — the two env vars
`KEYCLOAK_ISSUER_URI` / `KEYCLOAK_JWK_SET_URI` every service already exposes.

## Operate

| Action | Command |
|---|---|
| First start (build + up + wire) | `.\scripts\uat-up.ps1` |
| Restart / tunnel died / URL re-wire | `.\scripts\uat-up.ps1 -SkipBuild` |
| Deploy new code | `.\scripts\uat-up.ps1` (rebuilds jars + images) |
| Stop, keep data | `.\scripts\uat-down.ps1` |
| Destroy everything | `.\scripts\uat-down.ps1 -Wipe` |

Operator-only loopback ports: Keycloak admin `http://localhost:18090/auth/admin/`
(admin / password in `docker/uat.env`), Connect REST `http://127.0.0.1:18093`,
cloudflared metrics `http://127.0.0.1:12000`.

## The restart contract (quick-tunnel tax)

Every time the **cloudflared container** is recreated the public URL changes and all
sessions die. `uat-up.ps1 -SkipBuild` re-wires everything in ~2–4 min: discovers the new
URL (`/quicktunnel` metrics endpoint), rewrites `docker/uat.env`, recreates only the
env-affected containers (Keycloak + services + console), re-points the `native-console`
client's redirect URIs via kcadm, re-PUTs Debezium connectors (no-op), smoke-tests.
`cloudflared` runs with `restart: "no"` **on purpose** — a silent auto-restart would mint
a new URL while everything still validates the old issuer; fail loud instead.
Postgres data (tenants, Keycloak users) survives; testers just re-login at the new URL.

## Security posture (public stack)

- Signup-only UAT: committed seed users (`owner-acme`, `cashier-acme`) are **disabled**;
  testers self-register through the rate-limited public signup (the real product flow).
- `native-gateway` client **disabled** (direct-access grants + committed secret = a
  password oracle); `native-admin` secret **rotated** to a generated value.
- Keycloak admin console + master realm blocked at the edge; admin only via loopback.
- No `/actuator` route at the edge — health/prometheus are never public.
- Generated secrets (KC admin password, `NATIVE_PII_KEY`, `NATIVE_PII_HMAC_KEY`,
  `NATIVE_GIFTCARD_CODE_KEY`) live only in gitignored `docker/uat.env`.
- Gateway rate limiting trusts the edge-written XFF (single entry := `CF-Connecting-IP`);
  realm has `bruteForceProtected=true`.

## Secrets ↔ volume coupling (IMPORTANT)

`docker/uat.env` and the `native-uat-pgdata` volume are a **pair**. The PII/gift-card
ciphertexts in Postgres and the KC admin password are useless/lost without the env file.
`uat-up.ps1` therefore **aborts** if the volume exists but `uat.env` is missing — either
restore the file or `.\scripts\uat-down.ps1 -Wipe` and start fresh. Back up `uat.env` if
the UAT data matters.

Realm re-import note: Keycloak stores its realm in Postgres (`keycloak` DB); the realm
JSON imports only into an **empty** DB. To force a re-import of `native-realm.json`:
`docker exec native-uat-postgres psql -U postgres -c "DROP DATABASE keycloak WITH (FORCE)"`,
recreate it (`CREATE DATABASE keycloak OWNER keycloak`), then recreate the keycloak
container and re-run `uat-up.ps1 -SkipBuild` (wipes KC users, not service data).

## Known gaps / accepted trade-offs

- **Quick tunnels are best-effort**: no SLA, occasional Cloudflare 1033 errors, blocked
  on some corporate networks. The upgrade path is a named tunnel (free Cloudflare
  account + a domain) — stable hostname, no re-wiring, same compose file.
- **Kafka is ephemeral** (no volume): recreating the kafka container loses topics;
  connectors re-snapshot the outbox tables and consumers dedupe on the durable event id
  (idempotent consumers, rule 3) — duplicate-processing log lines are cosmetic.
- **self-order SPA is not deployed** (it has no Dockerfile yet); its gateway routes
  exist and are rate-limited, but the console's QR deep-links point nowhere public.
- Java containers are capped at 512 MiB each (~282 MiB heap) — fine for UAT traffic,
  not a load-test target.

## Demo tenant (optional appendix)

Signup is the intended flow. If a canned demo tenant is ever needed instead: re-enable
`owner-acme` and **set a new password** via KC admin, then insert the company row the
user's claim points at (as superuser, bypassing RLS):
`docker exec native-uat-postgres psql -U postgres -d org_service -c "INSERT INTO company (id, name, ...) VALUES ('11111111-1111-1111-1111-111111111111', 'Acme', ...)"`
— check the current `company` schema first; this is deliberately not scripted.
