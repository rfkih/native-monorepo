# 0004. Use springdoc-openapi (3.0.x) for OpenAPI docs, piloted in finance-service

- **Status:** Accepted (pilot) — extended fleet-wide by [ADR 0008](0008-openapi-docs-fleet-rollout.md)
- **Date:** 2026-06-20
- **Deciders:** rifki + Claude (pairing)
- **Related:** [CLAUDE.md](../../CLAUDE.md) (REST/OpenAPI external; "no stack change without an ADR"),
  [ENGINEERING-STANDARDS §1 (API)](../ENGINEERING-STANDARDS.md), the `springdoc` pin in
  `gradle/libs.versions.toml`

## Context
ENGINEERING-STANDARDS calls for REST/OpenAPI on external APIs, but no service currently emits an
OpenAPI document — the spec is implicit in the controllers. A probe had added
`springdoc-openapi-starter-webmvc-ui:2.8.13` to restaurant-service to test feasibility. Two facts
make the **version** a real decision, not a detail:

- The stack is pinned to **Spring Boot 4.1 / Spring Framework 7**.
- springdoc **2.8.x targets Boot 3 / Framework 6**; on Framework 7 it returns `/v3/api-docs` as a
  **Base64-encoded blob**. The **3.0.x** line is the one built for Boot 4 / Framework 7 (3.0.0
  upgraded to Boot 4 + Framework 7 API-versioning; 3.0.1 fixed the Base64 `/v3/api-docs` bug). So the
  probe's `2.8.13` was the wrong pin even though the context happened to boot.

Adopting an API-docs library across every service is cross-cutting and need not happen all at once; we
want to validate the dependency on Boot 4 in one service before a fleet-wide rollout.

## Decision
We will use **springdoc-openapi 3.0.x** (pinned `springdoc = "3.0.3"` in the version catalog) as the
OpenAPI documentation library, adopted **only in finance-service for now** (the pilot) via
`springdoc-openapi-starter-webmvc-ui`. The 2.8.x line is rejected (Boot 3 only). **Out of scope:**
rolling springdoc into the other services, publishing docs publicly through the gateway, and any
custom OpenAPI grouping / security-scheme modelling — each is a later, separate decision.

## Consequences
- finance-service now serves `/v3/api-docs` (OpenAPI 3.1 JSON) and `/swagger-ui`, generated from the
  live controllers — no hand-maintained spec to drift.
- **Enforcement:** `OpenApiDocsSmokeTest` boots the service and asserts `/v3/api-docs` is real
  OpenAPI JSON (not Base64) and documents the statements endpoints, so a Boot/springdoc bump that
  breaks doc generation fails the build instead of silently shipping a broken `/swagger-ui`.
- **Security posture:** in non-dev the docs endpoints sit behind the service's JWT
  `SecurityFilterChain`, and the gateway does not route `/v3/api-docs` or `/swagger-ui`, so docs are
  reachable only in dev / in-cluster — never published to the internet. Exposing them publicly would
  be a deliberate, separate change (a gateway route + a `permitAll` for the docs path) and a new ADR.
- **Cost / follow-ups:** the version is catalog-pinned (not Boot-BOM-managed), so it must be bumped
  deliberately and checked against springdoc's Boot-compat matrix on each Boot upgrade. If the pilot
  succeeds, a follow-up ADR can extend the dependency to the other services and add it to CLAUDE.md's
  stack list.
