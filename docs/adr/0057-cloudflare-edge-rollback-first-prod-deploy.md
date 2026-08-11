# 0057. Cloudflare-tunnel edge + rollback-first production deployment (co-located VPS)

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** product owner (rifki) + tech-lead
- **Amends:** [0053](0053-environments-branching-release-pipeline.md) — its **edge decision** (interim Tailscale Funnel → target Caddy+LE) and its **rollback model** (manual "redeploy the previous digest") are superseded by this ADR. Everything else in 0053 (three environments, one image lineage, GitHub-Flow + release tags, the six-point QA gate, backward-compatible migrations) stands.
- **Related:** `docker/compose.prod.yml` + `docker/prod.env.example` + `docker/prod/edge.conf` + `docker/prod/cloudflared/config.yml.example`; `.github/workflows/images.yml` + `ci.yml`; [0048](0048-minio-object-storage-for-media.md) (MinIO), [0049](0049-business-and-employee-apps-outlet-terminal-auth.md) (two public origins), [0010](0010-distributed-tracing-otel.md) (observability + "Vault at split time")

## Context

ADR 0053 designed dev → UAT → prod on "the owner's VPS," assuming a mostly-empty host with a spare Tailscale-Funnel URL and ~100 GB disk. When the build-out started, the actual box (`middleware`, Ubuntu 22.04, 16 GB) broke three of those assumptions:

1. **It is not a blank prod host.** It runs an **unrelated live workload** (the "Blackheart" trading platform + a "vector" app: backend up 12 days, Postgres/Redis/Caddy up 3 weeks; load avg ~4). Native prod must **co-exist without ever degrading it**.
2. **Tailscale Funnel is exhausted.** Funnel allows only 3 ports per node (443, 8443, 10000) and **all three are already served by vector**. 0053's interim "prod on a Funnel URL, no domain" edge is impossible on this box.
3. **Disk is the binding constraint** — 23 GB free (`/` 72 % full; Docker already ~37 GB), against 0053's ~100 GB sizing. **RAM is fine** (~14 GB available).

The owner also raised the bar on the release mechanism: **enterprise-grade with reliable rollback on any deployment error**, and **AI agents as blocking quality gates** (code review + QA) so defects are caught before deploy. This ADR records how prod is actually built under these constraints.

## Decision

### 1. Host — co-locate on the shared VPS, hard-fenced
Native prod runs on `middleware` alongside Blackheart/vector, isolated so it can never starve the live workload:
- **Resource caps on every container** — a CPU ceiling (`cpus`) *and* a memory ceiling (`mem_limit`) in `compose.prod.yml`. Java services 1 CPU / 512 MB; infra sized explicitly. Total ≈ 8.5 GB, inside the ~14 GB headroom.
- **Isolation** — its own compose project (`native-prod`), network, and `native-prod-*` volumes; nothing shared with vector/blackheart.
- **Nothing published** except three `127.0.0.1` operator ports on **prod-unique numbers** (KC admin 19090, Connect 19093, cloudflared metrics 12001/12002). The public edge is the tunnel; the edge itself has **no host port**.
- **Disk** — trim reclaimable space, add a **disk > 80 % alert**, and **expand the disk before real load**. This is the known ceiling of co-location.

### 2. Edge — Cloudflare Tunnel (replaces 0053's Funnel→Caddy)
`cloudflared` (already installed on the box) dials **out** to Cloudflare — **no inbound ports**, so it sidesteps the Funnel exhaustion and coexists with vector, while keeping Postgres/Kafka/MinIO/Keycloak-admin internal (satisfies 0053 §5).
- **Interim (no domain yet):** two **quick tunnels** → ephemeral `*.trycloudflare.com`, one per public origin (business console + employee app, per ADR 0049). URLs change each restart; `scripts/prod-up.ps1` re-discovers and re-wires Keycloak + services.
- **Target (once a domain is on Cloudflare):** one **named tunnel** with ingress `app.<domain>` → edge:8080 and `me.<domain>` → edge:8081, stable URL, TLS + WAF/DDoS at Cloudflare's edge. Quick → named is an **edge-only cutover** (set the URLs in `prod.env`, re-wire Keycloak once) — no application change. A domain remains **strongly recommended** for a real SaaS.

### 3. Rollback-first deployment (replaces 0053's manual rollback note)
"Reliable rollback on any deploy error" is delivered as **two layers**, because Flyway is forward-only:
- **App tier — automatic.** Every release is an **immutable manifest** pinning exact `@sha256` digests for all images. Prod deploys a manifest, never `:latest`. A `LAST_GOOD` pointer on the VPS makes `prod-rollback` a one-command, ~30–60 s revert.
- **Database tier — via backward-compatibility, not schema-revert.** A schema cannot be un-migrated. Clean rollback is therefore only possible when every migration is **expand/contract** (the new schema still serves the *old* image). This graduates from good-practice to an **enforced release invariant**: a **CI gate blocks any non-backward-compatible migration** (drop/rename/`NOT NULL` without default in the same release). A migration that must break compatibility **cannot be auto-rolled-back** and requires a maintenance window — the pipeline blocks it unless the window is explicitly accepted.

**Cutover = rolling + health-gate + auto-rollback.** Deploy: snapshot DBs (disaster net) → migrate (expand/contract only) → roll services one-by-one with per-container healthchecks → run the **smoke e2e** (incl. the money assertion `revenue == recorded sales`). **Any failure auto-reverts to the previous manifest and alerts.** A **deploy lock** (flock) prevents a deploy colliding with a manual op. (Blue-green — instant edge-flip rollback, ~0 downtime — is deferred: it wants a named tunnel + transient 2× app-tier headroom; revisit once a domain lands and disk is expanded.)

**DB snapshot is a disaster net, never the routine rollback:** restoring loses every transaction written since the snapshot — unacceptable for money postings. Routine rollback is app-tier only, which is *why* expand/contract is mandatory.

### 4. AI quality gates — blocking, before deploy
Layered on the deterministic CI (unit, Testcontainers, ArchUnit, event-contract, spotless, no-`SELECT *`, doc-drift), three **AI agents run as GitHub Actions jobs** (Claude Code headless) and **block the merge/release**:
- **AI code-review** (`code-reviewer` profile) — CRITICAL/High findings fail the check.
- **AI security-review** (`security-engineer` profile) — mandatory on any diff touching money / tenancy / auth / PII.
- **AI QA** (`qa-engineer` profile) — diff test-coverage + edge-case/bug hunt; blocks on critical gaps.

They need an `ANTHROPIC_API_KEY` repo secret. **Honest scope:** no gate guarantees zero bugs — these raise pre-deploy confidence; the health-gated **auto-rollback** is the backstop for what slips through. Together (deterministic tests + AI gates + e2e + auto-rollback) they are the defense-in-depth the owner asked for.

## Consequences

- **Prod coexists safely with a live trading platform** on one box — at the cost of hard resource caps and a real disk ceiling (23 GB free) that must be watched and expanded. True zero-downtime HA still wants a second node; single-VPS bounds it.
- **Reliable rollback becomes a property of the pipeline, not a hope** — pinned manifests + `LAST_GOOD` + health-gated auto-rollback, *guaranteed safe by the enforced expand/contract gate*. The one honest limit: a deliberately breaking migration needs a maintenance window and cannot be auto-rolled-back.
- **The edge is simpler than 0053's target** (Cloudflare terminates TLS — no Caddy/Let's-Encrypt to run) and unblocks "go live without a domain," while keeping a zero-app-change path to a real domain.
- **New artifacts (this ADR's follow-on, phased):** ✅ `compose.prod.yml` + `prod.env.example` + `prod/edge.conf` + `prod/cloudflared/config.yml.example` (Phase 1, this change); release-manifest/digest pinning + `images.yml` gaps (**`payment-service` + employee-web frontend are not yet built**) + a VPS GHCR read token (Phase 2); the AI + expand/contract CI gates (Phase 3); `deploy-prod.yml` (tag-triggered, protected Environment) + `prod-rollback` + branch protection + `QA-CHECKLIST.md` + Playwright e2e (Phase 4); first bring-up (Phase 5); backups + disk alert + observability + uptime check (Phase 6).
- **Hardening debt recorded, not hidden:** per-service + superuser Postgres passwords are still the literals from `./postgres/init` (mitigated — Postgres has no host port and sits behind RLS on an internal network); generate real secrets + parametrize the init scripts before onboarding external tenants.

**Out of scope / deferred:** blue-green / zero-downtime cutover; a second node / HA; managed Postgres/Kafka; Vault (interim: `prod.env`, root-only + encrypted offsite — until Vault at split time, [0010](0010-distributed-tracing-otel.md)); Android-installer distribution through the prod edge (the UAT APK/OTA mounts are intentionally omitted from `prod/edge.conf`).
