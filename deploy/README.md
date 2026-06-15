# Native — Kubernetes deployment (Kustomize)

> **STATUS — AUTHORED, NOT YET VERIFIED AGAINST A REAL CLUSTER.**
> These manifests and the CI image/push/deploy steps were **statically validated only**
> (`docker build` for the images; `kubectl kustomize <overlay>` for every overlay — they
> render into well-formed, structurally valid Kubernetes objects). They have **NOT** been
> applied to a live cluster, pushed to a registry, or run on a GitHub Actions runner.
> A server-side schema check (`kubectl apply --dry-run=client`/`--server`) and an actual
> `apply` remain **TODO** and require a reachable cluster. Treat everything here as a
> starting point to wire your real infra, not a turnkey deploy.

These are [Kustomize](https://kubectl.docs.kubernetes.io/references/kustomize/) manifests
(built into `kubectl` — **no Helm**). One `base/` defines the common per-service shape;
each `overlays/<service>/` pins that service's name, image, env, and secrets.

```
deploy/
  base/                     # common Deployment + Service shape
    deployment.yaml         #   non-root, probes, resource bounds, env from ConfigMap+Secret
    service.yaml            #   ClusterIP, port 80 -> container 8080
    kustomization.yaml
  overlays/
    gateway/                # the ONLY externally-exposed service (adds an Ingress); no DB
    org-service/            # DB owner; event producer; tenant bootstrap
    restaurant-service/     # DB owner; event producer
    carwash-service/        # DB owner; Kafka consumer; Redis (entitlement cache)
    finance-service/        # DB owner; Kafka consumer (SaleRecorded -> ledger)
    employee-service/       # DB owner; Kafka consumer; holds the PII encryption key (Secret)
    notification-service/   # DB owner; Kafka consumer (ConsolidationClosed)
    entitlement-service/    # DB owner; Kafka consumer + producer; Redis
```

## What the base gives every service

- **Deployment** — `replicas: 2`, a non-root `securityContext`
  (`runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, all
  capabilities dropped, `seccompProfile: RuntimeDefault`), a writable `/tmp` emptyDir,
  CPU/memory requests + limits, and the actuator health probes already enabled in every
  service's `application.yml`:
  - **liveness** → `GET /actuator/health/liveness` (cheap; never gated on DB/Kafka, so a
    transient downstream blip never gets the pod killed)
  - **readiness** → `GET /actuator/health/readiness` (per service: aggregates `db` and/or
    `kafka` where that service configures them — see each `application.yml`)
  - **startup** → liveness path, generous `failureThreshold` for slow JVM starts
- **Service** — `ClusterIP`, port `80` → container port `8080`.
- **Env** — `SPRING_PROFILES_ACTIVE=prod` (activates the `!dev` resource-server JWT
  validation + JSON logging), plus `envFrom` a per-overlay **ConfigMap** (non-secret:
  Kafka bootstrap, Keycloak issuer/JWKS, Redis host, downstream route URIs) and a
  **Secret** (DB creds, the PII key). Both are `optional: true` so the gateway (no Secret)
  is happy.
- **Image** — a kustomize image placeholder `native-service`; each overlay rewrites it to
  `REGISTRY/native/<service>:<tag>`.

## External / managed dependencies — NOT authored here

Postgres, Kafka (+ Schema Registry + Debezium Connect), Keycloak, and Redis are treated as
**external managed infrastructure**, referenced only by env/Secret. **No StatefulSets,
no operators, no broker manifests are authored in this repo.** In production these are run
by a managed service or a dedicated operator (e.g. CloudNativePG / a Postgres managed
offering, Strimzi or a managed Kafka, a Keycloak operator, a managed Redis), owned outside
this app repo. The overlays point at them via in-cluster DNS placeholders such as
`postgres.native.svc.cluster.local` / `kafka.native.svc.cluster.local` — **change these to
your real endpoints.**

Per `CLAUDE.md` rule 1, each service still owns its **own database** and connects as its
**own non-superuser role** (so Postgres RLS is genuinely enforced) — the overlay DB_URLs
already point each service at its own database (`org_service`, `finance_service`, ...),
mirroring `docker/postgres/init/01-init-databases.sql`.

## Secrets are PLACEHOLDERS — real creds come from Vault

Every `DB_PASSWORD` (and `NATIVE_PII_KEY` for employee-service) is `CHANGE_ME_VAULT`. **Do
not commit real secrets.** Per `CLAUDE.md`, production credentials come from **Vault** (short-TTL
dynamic DB credentials) or a sealed-secret, injected at deploy time. Options, pick one:

- **Vault Agent / VSO (Vault Secrets Operator)** — Vault writes the `service-secret` Secret;
  drop the placeholder `secretGenerator` literals and let Vault own the Secret.
- **Sealed Secrets / SOPS** — commit an *encrypted* secret and decrypt in-cluster.
- **External Secrets Operator** — sync from your cloud secret manager.

The `secretGenerator` in each overlay exists so the manifests render end-to-end and to
document exactly which keys each service needs; replace it with your real secret source.

## Validate locally (what was actually run)

```bash
# Render an overlay (proves kustomize builds + the manifests are well-formed):
kubectl kustomize deploy/overlays/org-service

# Server-side schema validation + a real apply need a reachable cluster (NOT done here —
# this environment has no cluster). Once you have one:
kubectl apply --dry-run=server -k deploy/overlays/org-service     # schema-validate
kubectl apply              -k deploy/overlays/org-service          # real apply
```

## Deploy for real — checklist

1. **Build + push images** (CI does this once a registry is configured; or by hand):
   ```bash
   docker build -f services/org-service/Dockerfile -t <REGISTRY>/native/org-service:<TAG> .
   docker push <REGISTRY>/native/org-service:<TAG>
   ```
   The Docker build **context is the repo root** (the Dockerfiles COPY `gradlew`, `gradle`,
   `build-logic`, `libs`, and `services/<svc>` from there).
2. **Set the image** in each overlay — replace `REGISTRY/native/<service>` + `newTag` with
   your real registry ref and the immutable tag CI produced (the commit SHA).
3. **Create the namespace**: `kubectl create namespace native`.
4. **Provide the connection Secrets** (DB creds per service, the PII key for
   employee-service) from Vault / your secret source — replace the `CHANGE_ME_VAULT`
   placeholders.
5. **Point env at your real infra** — edit the ConfigMap literals (Postgres/Kafka/Keycloak/
   Redis hostnames) in each overlay to your managed endpoints.
6. **Gateway Ingress** — in `overlays/gateway/ingress.yaml` set the real host
   (`api.example.co.id` → yours), the TLS Secret (`gateway-tls`, e.g. via cert-manager),
   and the ingress class/annotations your controller uses. The gateway is the **only**
   externally-exposed service; everything else stays ClusterIP-only.
7. **Apply** (per service, the deploy unit):
   ```bash
   for svc in gateway org-service restaurant-service carwash-service \
              finance-service employee-service notification-service entitlement-service; do
     kubectl apply -k deploy/overlays/$svc
   done
   ```
   (There is intentionally **no** aggregate `deploy/kustomization.yaml`: every overlay
   generates a `service-config`/`service-secret` pair, and combining them in one build
   collides on those generator names. Per-service apply — which also matches a GitOps
   per-service ArgoCD Application — is the deploy unit.)
8. **Database migrations** — Flyway runs in-process on startup here. For zero-downtime /
   rollback-safe deploys, move it to a **pre-deploy Job** using the expand/contract pattern
   (see `devops-engineer.md`); never let a deploy auto-apply a destructive migration.

## Not yet wired (future, per `devops-engineer.md`)

OpenTelemetry export, Linkerd (mTLS) at the service-split point, ArgoCD/GitOps CD with the
prod approval gate, Trivy/cosign in CI, and HPAs. Out of scope for this author-only task.
