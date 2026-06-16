# DEVLOG — history, key decisions, current status

> **For an AI agent:** this is the durable record of *what was built, why, and where we are* — the
> decisions especially (the code shows the *what*; this shows the *why*, which you can't re-derive).
> Keep it current: when you finish a milestone or make a design decision, add a dated line. The live
> task list is ephemeral; this file is the memory. Update the **Current status** section as you go.

## Current status (update me)
**Backend: complete, hardened, and proven end-to-end.** All of Phase 0–3 backend is built, every
milestone team-built → adversarially reviewed (code + security + domain-correctness) → fix-rounds →
full build green → committed. The validation slice runs **live** (sale → outbox → Debezium → Kafka →
finance → consolidated revenue = verified against real infra, not just Testcontainers). CI + Kustomize
deploy authored (unverified vs a real cluster). The whole codebase is **package-by-feature with
controller/service/repository/domain/dto/messaging layer sub-packages**, ArchUnit-enforced.

**Not done (hard gates — need a human/SME/infra, do NOT invent):**
- Frontends (console onboarding wizard + dashboard, employee PWA) — design decisions, never autonomous.
- **Official DJP/BPJS statutory figures** — payroll ships `ILLUSTRATIVE_PLACEHOLDER` data (provenance
  column + loud seed + runtime flag); a tax SME must seed real effective-dated figures.
- **Full IAS-21 multi-currency consolidation** (CTA/OCI, historical-rate equity, opening-balance
  roll-forward) — ships a FLAGGED-SIMPLIFIED translation; needs an accounting SME.
- Live infra (a real registry/cluster/secrets for the deploy; a real notification transport).

**Open follow-ups (tracked):** org-tree move/deactivate *semantics* (needs a decision); notification
real provider (needs a transport choice); payroll expected-source registry (needs a rule);
finance-expansion robustness scoping; a few deferred operational items (member_group_index backfill, a
within-company concurrent-close lock, two MVC integration tests).

## Key design decisions (the why)
- **Package root `id.co.nativeapp`** — `id.co` reverse-domain; `nativeapp` because `native` is a Java
  reserved word (illegal package segment).
- **Layered + package-by-feature, ArchUnit-enforced** (not hexagonal) — controller→service→repository→
  domain, grouped by capability; later refactored so each feature has explicit
  `controller/service/repository/domain/dto/messaging` sub-packages (user preference for readability).
  The gateway is the documented carve-out (reactive edge, not aggregate-bearing).
- **The `*Writer` pattern** — every `@Transactional` write is its own `@Component` (`*Writer`), so it is
  invoked through the Spring proxy: a self-invocation would bypass the tx advice AND the
  `RlsAutoApplyAspect` that sets the tenant GUC. Load-bearing for RLS. Services orchestrate, Writers
  transact, Readers query.
- **RLS is enforced, not assumed** — every service connects as its own non-superuser role; tables use
  `ENABLE`+`FORCE ROW LEVEL SECURITY`; the tenant is a Postgres GUC (`app.current_tenant`) set per
  transaction via a scoped value (not ThreadLocal). Tested as the non-superuser `app_user`.
- **Group consolidation cross-tenant model (P3d)** — a group is a SECOND RLS scope: a new
  `app.current_group` GUC + a CONJUNCTION policy `group_id = app.current_group AND company_id =
  app.current_tenant` on the group tables. Members PUBLISH their trial balances (TrialBalancePublished),
  finance never cross-tenant-reads. Adversarially verified bypass-free. **Decision:** single-reporting-
  currency consolidation is fully correct; multi-currency is FLAGGED-SIMPLIFIED (balance-check gate +
  residual to a flagged CTA reserve + `uses_simplified_translation_policy`); full IAS-21 deferred to an
  SME. (Memory: `p3d-consolidation-scope`.)
- **CDC wire = base64'd Avro bytes** — the outbox payload is `bytea` (raw Avro); Debezium decodes it as a
  `ByteBuffer` that `ByteArrayConverter` rejects, so the connector base64's it (`binary.handling.mode`)
  and the consumer's `libs/events Base64ByteArrayDeserializer` decodes it back. AvroSerde + the "no
  Confluent serde" design are unchanged. (See RUNBOOK gotcha #3.)
- **Flagged-illustrative domain data** — anywhere real domain law is needed but absent (statutory
  payroll figures, FX rates, consolidation policy), the MACHINERY is real but the DATA is loudly flagged
  (provenance enum + loud seed comment + a runtime flag on the run/event) so it can never be mistaken
  for verified production values. Never invent tax/accounting law as production values.

## Milestone history (newest first; commit refs are illustrative anchors)
- **Live end-to-end validation + 3 CDC fixes** — ran the real outbox→Debezium→Kafka→finance loop;
  found+fixed the publication-mode, occurred_at-timestamp, and bytea/ByteBuffer (base64) bugs the
  stubbed-relay tests couldn't catch. Proven: `GET /api/v1/revenue` = the recorded sales.
- **Follow-up hardening sweeps** — consolidation money-math regression-locks; FX/mapping resolution
  determinism + a group-RLS migration-lint guard; P3d tenancy/predicate/typed-fault hardening; platform
  defense (show-details pinned, encoded-JSON PII guard, readiness composition, RLS-bean presence);
  payroll/labor guards (mixed-grain, top-bracket-cap, control-total currency, looped race).
- **CI + deploy (#24)** — GitHub Actions (build+test+image matrix) + Kustomize base+overlays + fixed the
  broken service Dockerfiles + the missing entitlement DB. Author-only / unverified vs a real cluster.
- **Layer-subpackage refactor** — every feature split into controller/service/repository/domain/dto/
  messaging; ArchUnit retargeted; service-template + docs updated. Pure move, 624 tests green.
- **Phase 3 (P3a–P3d)** — payroll engine (flagged statutory) · finance consumes labor cost (supersession,
  concurrency-safe) · FX/multi-currency (non-float FxRate, flagged stub) · group consolidation
  (two-GUC RLS, intercompany elimination, FX translation, the ConsolidationClosed producer — closing the
  loop notification consumes). 5 seams, each gated + fixed + committed.
- **#14 cross-cutting hardening** — JSON structured logs, readiness probes, RLS-ordering + anti-redeclare
  + float-ban ArchUnit guards.
- **Phase 2** — entitlement-service + the gate lib · full org tree + legal_employer · employee records
  (PII field-encryption) · carwash (2nd vertical) · finance expansion (mapping rules + dimensional
  ledger + expenses) · notification-service.
- **Phase 1 (validation slice)** — gateway + Keycloak (M1.1) · org-service create-company (M1.2) ·
  restaurant record-sale (M1.4) · finance consume → consolidated revenue (M1.5) · event transport (M0.4).
- **Phase 0** — Gradle monorepo + Java 25 toolchain · shared libs (money/tenant/events) · service-template
  · the quality gates · the engineering-standards + code-structure docs.
