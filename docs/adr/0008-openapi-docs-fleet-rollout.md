# 0008. Roll springdoc-openapi out fleet-wide with an `@Operation` ArchUnit enforcer

- **Status:** Accepted
- **Date:** 2026-06-21
- **Deciders:** rifki + Claude (pairing)
- **Related:** [ADR 0004](0004-openapi-docs-springdoc.md) (the springdoc 3.0.x pilot this extends),
  [ENGINEERING-STANDARDS §0 scorecard #9](../ENGINEERING-STANDARDS.md) +
  [§1.3](../ENGINEERING-STANDARDS.md), [CLAUDE.md](../../CLAUDE.md) (stack: REST/OpenAPI external),
  the `springdoc` pin in `gradle/libs.versions.toml`

## Context
[ADR 0004](0004-openapi-docs-springdoc.md) validated **springdoc-openapi 3.0.x** on Spring Boot 4 /
Framework 7 in **one** service (finance — the pilot), deliberately leaving "rolling springdoc into the
other services" and "`@Operation` coverage + enforcement" out of scope. The competitive scorecard
(ENGINEERING-STANDARDS §0, dimension **#9 API docs**) recorded the residual gap precisely: *springdoc
fleet rollout + `@Operation` coverage + an ArchUnit enforcer*. blackheart (the benchmark) already ships
springdoc with `@Operation`/`@Tag` on all controllers, so Native was **behind** on this row until the
rollout landed. The pilot succeeded; this ADR records the decision to finish the rollout — the explicit
"if the pilot succeeds, a follow-up ADR can extend the dependency to the other services" that ADR 0004
named.

## Decision
Adopt springdoc **fleet-wide** on every service that exposes a **business REST API**, via the same
catalog-pinned `springdoc-openapi-starter-webmvc-ui` (3.0.3) the pilot used. Concretely, for each of
**org, restaurant, carwash, employee, entitlement** (joining the **finance** pilot):

1. **Dependency** — add `libs.springdoc.starter.webmvc.ui` so `/v3/api-docs` (OpenAPI 3.1) + `/swagger-ui`
   are generated from the live controllers.
2. **`@Operation` + `@Tag` on every handler** — each `@RestController` carries a class-level `@Tag`, and
   every HTTP handler a method-level `@Operation` (summary + description) lifted from its Javadoc, so the
   generated document has a human summary per endpoint, not a bare inferred `operationId`. (These are
   **developer-facing API documentation, not user-facing UI copy** — HR-9's i18n rule does not apply.)
3. **Smoke test** — an `OpenApiDocsSmokeTest` boots the real service and asserts `/v3/api-docs` is genuine
   OpenAPI JSON (not the Base64 blob the Boot-3 springdoc 2.8.x line returns on Framework 7) documenting
   that service's live endpoints, mirroring the finance pilot's guard.
4. **ArchUnit enforcer** — a new rule `apiHandlersAreDocumentedWithAnOperation` in each service's
   `LayeredArchitectureTest` fails the build if any `@RequestMapping`/`@GetMapping`/… handler on a business
   `@RestController` lacks an `@Operation`. The `config` `HealthController` (`/healthz`) is exempt
   (`resideOutsideOfPackage("..config..")`). The **same rule is added to `service-template`** (with
   `allowEmptyShould(true)`, since a fresh clone has no controller yet) so every future cloned service
   inherits both the dependency and the enforcer.

**Deliberate exclusions** (documented, not oversights):
- **notification-service** — exposes no business REST API (a pure event consumer; only `/healthz`). Adding
  springdoc would document nothing of value.
- **gateway** — the reactive Spring Cloud Gateway edge has no business endpoints of its own (it routes) and
  would need the *webflux* springdoc starter, not webmvc. Documenting the routed services individually is
  the right granularity.

**Out of scope (later, separate decisions):** publishing the docs publicly through the gateway (a gateway
route + `permitAll` for the docs path); richer `@ApiResponse` / `ProblemDetail` error-response modelling and
a "generated spec is a superset of a published spec" contract test (the API analogue of the event contract
tests — ENGINEERING-STANDARDS §1.3 still lists these as the fuller target); and custom OpenAPI grouping /
security-scheme modelling.

## Consequences
- Every business service now serves `/v3/api-docs` + `/swagger-ui`, generated from its controllers — no
  hand-maintained spec to drift. Scorecard **#9 flips to ✅** (Native ≥ blackheart: springdoc + `@Operation`
  + `@Tag` on all controllers, **plus** an ArchUnit enforcer and a per-service smoke test, where blackheart
  relies on discipline).
- **A new undocumented endpoint fails the build**, not review: the ArchUnit rule is part of `check`, and
  `service-template` carries it so the standard cannot silently lapse on a new service.
- **Security posture unchanged from ADR 0004.** In non-dev the docs endpoints sit behind each service's JWT
  `SecurityFilterChain`, and the gateway routes neither `/v3/api-docs` nor `/swagger-ui`, so docs stay
  dev/in-cluster — never published to the internet. Exposing them publicly remains a deliberate, separate
  change + ADR.
- **Cost / follow-ups:** the springdoc version stays catalog-pinned (not Boot-BOM-managed), so it must be
  bumped deliberately and checked against springdoc's Boot-compat matrix on each Boot upgrade — now guarded
  by **six** smoke tests across the fleet instead of one. The error-response/ProblemDetail modelling and the
  spec-superset contract test remain open follow-ups under ENGINEERING-STANDARDS §1.3.
