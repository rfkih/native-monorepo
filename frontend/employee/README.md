# Employee app (`frontend/employee`) — ADR 0049 P5

A dedicated, standalone self-service web app for employees: a personal OIDC login → the console's
`/me` surface (profile, payslips, time-off, expense claims, own sales/commission). It is **App #2**
of the ADR 0049 two-app split (App #1 = the console / Business terminal).

## How it relates to the console

It does **not** fork or copy console code. The `@` alias resolves straight into `../console/src`
(see `vite.config.ts`), so `Me`, `MyExpenses`, `MePayslipsScreen`, `MeTimeoffScreen`, the auth
layer, i18n, and the design-system components are the **same modules the console ships** — one
implementation, two front doors (mirrors `frontend/self-order`'s second-package shape, but self-order
reimplements a tiny client whereas this shares source).

Differences from the console app:

- **Personal login only.** No `device`/outlet login, no PIN operators, no owner/manager elevation —
  `auth.elevate()` is never called, so the elevation `UserManager` inside `@/lib/auth` stays dormant.
- **No POS, no back-office, no `Shell`.** Just the four `/me` routes; `*` → `/me`.
- **Minimal providers:** `QueryClient` + `Auth` + `Session` + `BrowserRouter` + i18n. No
  `OperatorSessionProvider`, no `PrinterProvider`, no PWA.

No backend change: `/api/v1/me/**` and its `ME_ROLES` gate already exist.

## Dev

```sh
cd frontend/employee
npm install
npm run dev          # http://localhost:5175 (strictPort); /api proxied to VITE_GATEWAY_URL
```

`npm run dev` needs the employee origin registered as a Keycloak redirect URI (see below) and a
reachable gateway (`VITE_GATEWAY_URL`, default `http://localhost:8090`).

## Build / verify

```sh
npm run build        # tsc -b && vite build (full type-check of the shared ../console/src tree —
                     # relies on console's sibling node_modules for type resolution)
npm run lint
npm test             # vitest (no tests yet)
```

## Deploy image

```sh
# Context is the frontend/ PARENT (the build needs the shared console/src alongside employee/).
docker build -f frontend/employee/Dockerfile -t native-uat/employee:latest frontend
```

The image runs `vite build` (not the full `tsc -b && vite build`) — the bundle needs no type-check,
and full type-checking would drag in console's own `node_modules`. A build-stage symlink points
`/app/console/node_modules` at the employee install so the shared `console/src` files resolve every
dependency (react as the **same** instance — no dual-package hazard) from the one install. Runtime
Keycloak/API URLs are injected at container start from `NATIVE_*` env (`docker-entrypoint.d/`), so
one image serves any environment.

## UAT deploy — a SECOND funnel origin (LIVE)

The console owns the `:8443` funnel origin and `${origin}/auth/callback`. Two apps cannot share one
origin's callback, so the Employee app gets its **own** origin on the Tailscale funnel's second
allowed port, **:10000** — while still issuing tokens from the **same** Keycloak (`:8443/auth`) so
the token `iss` the services validate is unchanged. `/api` is same-origin on `:10000` (no CORS); the
browser reaches Keycloak at `:8443/auth` directly (a full-page redirect), and KC redirects back to
`:10000/auth/callback` (served by this SPA via its history fallback).

> **Live since 2026-08-09** at `https://a8.tailbf9662.ts.net:10000` (probe tenant). Verified: the
> origin serves the employee SPA, "Sign in" redirects to Keycloak with the `:10000/auth/callback`
> redirect_uri + PKCE, and KC accepts it (login form renders). The console `:8443` origin is
> unaffected. `scripts/uat-up.ps1` now builds the employee image and registers **both** origins on
> the `native-console` client, so a full bring-up keeps the Employee app working (no manual re-add).
> The Tailscale funnel `:10000` mapping persists out-of-band, like the main `:8443` funnel.

### 1. Build the image
```sh
docker build -f frontend/employee/Dockerfile -t native-uat/employee:latest frontend
```

### 2. `docker/compose.uat.yml` — add the service (after the `console` service)
```yaml
  employee:
    image: native-uat/employee:latest
    container_name: native-uat-employee
    restart: unless-stopped
    mem_limit: 64m
    environment:
      NATIVE_AUTH_MODE: oidc
      NATIVE_API_BASE_URL: ""            # same-origin: relative /api through the :8081 edge block
      NATIVE_KEYCLOAK_URL: ${PUBLIC_URL}/auth   # SAME issuer as console (token iss must match)
      NATIVE_KEYCLOAK_REALM: native
    healthcheck:
      test: ["CMD", "wget", "-qO", "/dev/null", "http://127.0.0.1:8080/index.html"]
      interval: 10s
      timeout: 5s
      retries: 6
      start_period: 10s
```
And publish a second edge port (in the `edge` service `ports:` list):
```yaml
      - "127.0.0.1:8089:8081"   # ADR 0049 P5: Employee origin (funnel :10000 -> here -> edge:8081)
```

### 3. `docker/uat/edge.conf` — add a second server block
```nginx
# --- ADR 0049 P5: the Employee app origin (funnel :10000 -> 127.0.0.1:8089 -> edge:8081) ---
# A second single-origin surface: / -> employee SPA, /api -> gateway (same-origin, no CORS).
# Keycloak is reached by the browser at the console origin (:8443/auth, the token issuer) directly,
# so this origin needs only /api + the SPA — its own /auth/callback lands via the SPA fallback.
server {
    listen 8081;
    server_name _;
    resolver 127.0.0.11 valid=10s ipv6=off;
    absolute_redirect off;
    client_max_body_size 20m;
    set $client_ip $http_cf_connecting_ip;

    location /api/ {
        set $gw http://gateway:8080;
        proxy_pass $gw;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-For $client_ip;
    }

    location / {
        set $employee http://employee:8080;
        proxy_pass $employee;
        proxy_set_header Host $host;
    }
}
```

### 4. Bring it up (additive; the `--no-deps` avoids touching other services)
```powershell
docker compose --env-file docker/uat.env -f docker/compose.uat.yml up -d --no-deps employee
docker compose --env-file docker/uat.env -f docker/compose.uat.yml up -d --no-deps --force-recreate edge
```

### 5. Tailscale funnel — expose :10000 (funnel allows 443 / 8443 / 10000 only)
```sh
tailscale funnel --bg --https=10000 http://127.0.0.1:8089
```

### 6. Keycloak — ADD (do not overwrite) the employee origin to the `native-console` client
Run from **PowerShell** (git-bash mangles `/opt/...` paths). Append `${PUBLIC_URL%:8443}:10000/*`
(e.g. `https://a8.tailbf9662.ts.net:10000/*`) to the client's `redirectUris` **and**
`post.logout.redirect.uris`, keeping the existing `:8443/*` entry. Note `uat-up.ps1` (line ~212)
OVERWRITES `redirectUris` to a single origin on every full bring-up, so re-add `:10000/*` after any
`uat-up.ps1` run (or extend that script to include both origins).

### 7. Verify
Browse `https://<funnel-host>:10000` → "Sign in" → Keycloak (a normal employee, e.g. Rina Kasir) →
lands on `/me` with payslips / time-off / claims. Confirm `/api/v1/me/**` calls succeed (same-origin
`:10000/api` → gateway; token `iss` = `:8443`).

## Native shell

`frontend/native-employee/` (a Capacitor shell, appId `id.co.nativeapp.employee`) is a thin
`server.url` client that points at this app's origin — see that package's README for the APK build.
