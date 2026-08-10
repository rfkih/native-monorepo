# 0053. Environments, branching, and the release pipeline (dev → UAT → prod on a VPS)

- **Status:** Accepted
- **Date:** 2026-08-10
- **Deciders:** product owner (rifki) + tech-lead
- **Related:** `.github/workflows/ci.yml` + `images.yml` (GHCR image publish), `scripts/uat-up.ps1` + `docker/compose.uat.yml` (the UAT stack), [0010](0010-distributed-tracing-otel.md) (observability + the "at split time: Vault" horizon), the CLAUDE.md "migrations are high-risk" / "event schema changes backward-compatible only" rules, docs/RUNBOOK.md

## Context

Native has grown a real CI + registry but no formal path to production:

- **dev** — local Docker (`compose.dev.yml` + `compose.local-override.yml`), images built from source.
- **UAT** — the full stack on the maintainer's machine behind a **stable Tailscale Funnel** URL, brought up
  by `scripts/uat-up.ps1`, which **builds the images locally** (`native-uat/*`) rather than pulling them.
- **CI** — `ci.yml` builds + tests the fleet on push/PR to `master`; `images.yml` builds + **pushes
  versioned images to GHCR** (`:sha` + `:latest`) on merges to `master`. **Nothing consumes those images.**
- **prod** — does not exist. There is no named gate between "a green build" and "live."

The owner wants a real dev/UAT/prod separation, a branching model, a **mandatory QA gate before prod**, and
**production on a VPS** (they have one; **no domain registered yet**). Forces: a heavy stack (Postgres,
Kafka + Connect, Keycloak, MinIO, Redis, 8+ services, gateway, edge); a solo maintainer; money/tenancy/PII
correctness; Flyway migrations are **forward-only and high-risk**; events are **backward-compatible only**.

## Decision

Three environments sharing **one image lineage**, **GitHub-Flow branching with release tags**, and a
**six-point QA gate** that must pass before a production tag.

1. **Image lineage (the core rule).** An image is built **once**, on merge to `master` (`images.yml` →
   GHCR `:sha`). UAT and prod **pull** it; neither rebuilds. **Prod deploys the exact digest that passed QA
   on UAT.** `compose.uat.yml` switches from build-local to **pull-GHCR** so UAT exercises prod's real deploy
   path. What was tested is what ships.

2. **Environments.**
   - **dev** — `compose.dev.yml`, from source, throwaway, dev defaults.
   - **UAT / staging** — `compose.uat.yml` pulling GHCR `:sha`, **auto-deployed on every merge to `master`**;
     the QA environment; its own `uat.env`; Tailscale Funnel.
   - **prod** — new `compose.prod.yml` on the owner's **VPS**, pulling the **QA'd digest**; its own `prod.env`
     (**secrets never reused from UAT**); durable named volumes + backups. **Edge (interim): Tailscale Funnel**
     — a stable `*.ts.net` URL with TLS handled, so prod can go live **without a domain**. **Target:** register
     a domain, then **Caddy + automatic Let's Encrypt** — an **edge-only swap**, no application change.

3. **Branching — GitHub Flow + release tags.** `master` is the protected, always-green trunk (**PR + green CI
   required; no direct push**). `feat/*` / `fix/*` are short-lived → squash-merge. **Merge to `master`
   auto-deploys UAT.** An **annotated tag `vX.Y.Z`** (SemVer; **one version for the whole fleet** — services
   share an event contract and move together) is the **only** thing that deploys prod, via a protected
   `deploy-prod` workflow (**GitHub Environment, required reviewer = owner**). `hotfix/*` branches off the
   released tag, PRs to `master`, ships as a patch tag.

4. **The QA gate — all six must pass before a production tag.**
   1. **CI suite green** on the candidate (unit, Testcontainers integration, ArchUnit, event contract tests,
      spotless, no-`SELECT *`, doc-drift).
   2. **Critical-path e2e on UAT** (Playwright): signup → login per role → POS sale → payment capture → daily
      close → payroll run, incl. the money assertion `revenue == recorded sales`.
   3. **Migration safety** — every Flyway migration reviewed (data-engineer) and **dry-run on a UAT DB restored
      from a prod snapshot**; must be **backward-compatible (expand/contract)**.
   4. **Soak, 24–48 h on UAT** — watch Kafka/Debezium consumer lag, the error-inbox, and resource use.
   5. **Security review PASS** for any change touching money, tenancy, auth, or PII.
   6. **Owner acceptance sign-off** against `docs/QA-CHECKLIST.md` (money, tenancy, auth, each vertical POS,
      payroll, plus the touched feature). The one deliberately-human step.

5. **Prod operations.** Only the edge is public (interim Funnel / target `:443`); **Postgres, Kafka, MinIO,
   Redis, and the Keycloak admin console are firewalled to the internal network**. **Nightly encrypted
   `pg_dump`** of every service DB + the Keycloak DB, plus **MinIO offsite object sync**, retained 7–30 days,
   **restore-tested monthly**. **Rollback = redeploy the previous digest** — which is *why* migrations must be
   backward-compatible: **an image rollback never triggers a DB rollback**. Observability via
   `compose.observability.yml` (Prometheus + Grafana; OTEL already fleet-wired) + alerts (service down,
   error-inbox spike, Kafka lag, disk > 80 %) + an **external `/health` uptime check**.

**Out of scope / deferred:** managed Postgres/Kafka (self-hosted on the VPS for now); multi-node / HA;
blue-green / zero-downtime (accept brief per-service restarts + a maintenance window for breaking changes);
Vault (`prod.env` on the VPS — root-only + an encrypted offsite copy — until Vault at split time, [0010](0010-distributed-tracing-otel.md)).

## Consequences

- **What you test is what ships** (the same digest travels UAT → prod); prod is **reproducible from a version
  tag**. A release now passes a **named, repeatable gate** — nothing reaches customers without CI + e2e +
  migration safety + soak + security + owner sign-off.
- **UAT stops building images** — faster, and it runs prod's exact pull-and-deploy path.
- **The backward-compatible-migration rule graduates from good-practice to a release invariant**: a migration
  that isn't expand/contract **blocks rollback** and must be split. (Reinforces the existing forward-only +
  additive-events rules.)
- **New artifacts to build (the follow-on to this ADR):** `compose.prod.yml` + `prod.env.example` + a
  `Caddyfile` (target edge); `deploy-uat.yml` (auto on `master`) + `deploy-prod.yml` (on tag `v*`, protected
  Environment); `compose.uat.yml` → pull-GHCR; `docs/QA-CHECKLIST.md` + a Playwright `e2e/` suite in CI;
  branch-protection tightening on `master`; VPS provisioning + firewall + backups + observability wiring.
- **Cost / limits:** single-VPS self-hosting bounds scale and uptime — revisit managed services + HA when load
  or an SLO demands. **No domain yet** means prod runs on a stable Funnel URL; **registering a domain is
  strongly recommended** for a real SaaS and is an **edge-only cutover** (Funnel → Caddy) with zero app change.
- Provisioning specifics (provider, OS, exact sizing) are taken from the owner's existing VPS when the build
  starts; the stack wants roughly **8 GB RAM / 4 vCPU / 100 GB+ SSD**, Ubuntu LTS.
