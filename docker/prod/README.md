# Native — production stack (`compose.prod.yml`)

The full platform **co-located on the VPS `middleware`** alongside an unrelated live workload
(Blackheart/vector). Design & rationale: **[ADR 0057](../../docs/adr/0057-cloudflare-edge-rollback-first-prod-deploy.md)**
(amends [ADR 0053](../../docs/adr/0053-environments-branching-release-pipeline.md)).

> **Status: Phases 1–4 built; NOT yet deployed.** The stack definition (P1), complete GHCR
> image set + digest pinning (P2), CI/AI/migration gates (P3), and the tag-triggered deploy
> pipeline with auto-rollback (P4: `deploy-prod.yml` + `scripts/prod-deploy.sh` /
> `prod-rollback.sh` + `docs/QA-CHECKLIST.md` + `e2e/`) all exist. Remaining: Phase 5
> (first bring-up: `prod.env` secrets + quick tunnels + KC wiring on the VPS) and Phase 6
> (backups/alerts/observability). Do **not** hand-run `docker compose up` against this
> file — every deploy goes through the health-gated pipeline (or `prod-deploy.sh` directly).

## What's here
| File | Purpose |
|---|---|
| `../compose.prod.yml` | The stack: 11 services + console + employee + edge + tunnels. Pulls images from GHCR; hard CPU+memory caps; `127.0.0.1`-only operator ports; `native-prod` isolation. |
| `../prod.env.example` | Template for `docker/prod.env` (gitignored). Every secret + the image ref + public URLs. |
| `edge.conf` | nginx edge — business origin (:8080) + employee origin (:8081). |
| `cloudflared/config.yml.example` | Named-tunnel config for the **target** edge (once a domain exists). |

## How it will be operated (Phases 2–5)
- **Images (Phase 2 ✅ in the repo):** `images.yml` now builds **all 13** prod images —
  the 11 services (incl. `payment-service`) + `console-web` + `employee-web`. They publish to
  GHCR on merge to `master` (editing the workflow force-rebuilds the whole set). Two operator
  steps remain (owner actions, not code):
  1. **GHCR read token on the VPS** — a GitHub PAT (classic) with `read:packages`:
     ```bash
     echo <PAT> | docker login ghcr.io -u <github-user> --password-stdin
     ```
     (GHCR packages are private on first push; a token is required to pull.)
  2. **Pin the release to digests** — after the images build for a release ref:
     ```powershell
     ./scripts/release-manifest.ps1 -Tag <git-sha-or-version>   # -> docker/compose.prod.images.yml
     ```
     Deploy/rollback with both files so prod runs the exact `@sha256` that passed QA:
     `docker compose -f compose.prod.yml -f compose.prod.images.yml -p native-prod up -d`.
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
