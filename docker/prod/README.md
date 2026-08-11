# Native — production stack (`compose.prod.yml`)

The full platform **co-located on the VPS `middleware`** alongside an unrelated live workload
(Blackheart/vector). Design & rationale: **[ADR 0057](../../docs/adr/0057-cloudflare-edge-rollback-first-prod-deploy.md)**
(amends [ADR 0053](../../docs/adr/0053-environments-branching-release-pipeline.md)).

> **Status: Phase 1 (artifacts only).** These files exist and are reviewable; **nothing is
> deployed yet**. The bring-up script (`scripts/prod-up.ps1`), GHCR image readiness, the
> CI gates, and `deploy-prod.yml` land in Phases 2–5. Do **not** hand-run `docker compose up`
> against this file — the deploy is health-gated with auto-rollback (Phase 4/5).

## What's here
| File | Purpose |
|---|---|
| `../compose.prod.yml` | The stack: 11 services + console + employee + edge + tunnels. Pulls images from GHCR; hard CPU+memory caps; `127.0.0.1`-only operator ports; `native-prod` isolation. |
| `../prod.env.example` | Template for `docker/prod.env` (gitignored). Every secret + the image ref + public URLs. |
| `edge.conf` | nginx edge — business origin (:8080) + employee origin (:8081). |
| `cloudflared/config.yml.example` | Named-tunnel config for the **target** edge (once a domain exists). |

## How it will be operated (Phases 2–5)
- **Images (Phase 2):** prod PULLS `${IMAGE_BASE}/<svc>:${NATIVE_IMAGE_TAG}`. `images.yml` must
  first add **`payment-service`** + the **`employee-web`** frontend (not built today), and the
  VPS needs a GHCR read token (`docker login ghcr.io`). Tag is pinned to an `@sha256` digest.
- **Secrets:** copy `prod.env.example` → `prod.env`, generate FRESH values (`openssl rand -base64 32`).
  **Never reuse UAT secrets.** `chmod 600`, root-only, keep an encrypted offsite copy.
- **Edge:** interim = two Cloudflare **quick tunnels** (`--profile quicktunnel`), ephemeral URLs
  re-wired on each bring-up. Target = one **named tunnel** (`--profile namedtunnel`) on a domain.
- **Deploy (Phase 4/5):** tag `vX.Y.Z` → snapshot → migrate (expand/contract only) → rolling
  restart with healthchecks → smoke e2e → **auto-rollback to `LAST_GOOD` on any failure**.

## Guardrails (co-location — never degrade Blackheart/vector)
- Every container has a `cpus` + `mem_limit` ceiling. Total ≈ 8.5 GB.
- Nothing published except loopback operator ports: **KC admin `127.0.0.1:19090`**,
  **Connect `127.0.0.1:19093`**, **cloudflared metrics `127.0.0.1:12001/12002`**.
- Disk is the binding constraint (23 GB free) — watch it; expand before real load.
