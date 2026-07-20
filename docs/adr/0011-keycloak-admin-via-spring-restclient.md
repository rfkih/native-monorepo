# 0011. Use Spring RestClient for Keycloak Admin API calls (no keycloak-admin-client library)

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** rifki + Claude (pairing)
- **Related:** [CLAUDE.md hard rule 2](../../CLAUDE.md) (no synchronous business-to-business calls),
  [ENGINEERING-STANDARDS §4](../ENGINEERING-STANDARDS.md) (explicit outbound timeouts + startup
  self-check), [ENGINEERING-STANDARDS §6](../ENGINEERING-STANDARDS.md) (secrets via env, no PII in
  logs), Increment 1 Slice 1 (public sign-up)

## Context
The public sign-up flow (Increment 1 Slice 1) requires org-service to create a Keycloak user and
assign a realm role after creating the company. Two implementation options were considered:

1. **`keycloak-admin-client` (Keycloak's official Java library).** Ships with a RESTEasy HTTP
   client dependency (`resteasy-client`), which conflicts with the stack's Spring Web / RestClient
   model and adds a large transitive dependency tree (RESTEasy, Jackson 2.x overlapping with the
   Spring-managed Jackson 3.x, etc.). It also lacks explicit timeout configuration aligned with
   `@ConfigurationProperties`, meaning it cannot participate in the fleet-wide startup self-check
   (`OutboundClientTimeoutCheck`) without custom wrapping.

2. **Plain Spring `RestClient` with explicit timeouts.** No new Gradle dependency — `RestClient`
   is already on the classpath (Spring Web). The three Admin API calls needed for sign-up
   (user-exists check, create-user, assign-role) are straightforward HTTP operations. Timeouts are
   externalized via `KeycloakAdminProperties` (`@NotNull`, `@Validated`) and asserted positive at
   startup via `OutboundClientTimeoutCheck.requirePositive`, exactly matching the fleet pattern
   established for the JWKS fetch in `JwksClientProperties`.

The Keycloak Admin API is an **infrastructure edge to the identity provider**, not a
business-to-business synchronous call. Keycloak is a platform dependency of every service (every
JWT is validated against its JWKS). Calling its Admin REST API from org-service is therefore the
same class of edge as the JWKS fetch — not a business-service-to-business-service coupling — so
hard rule 2 holds.

## Decision
Use **Spring `RestClient`** for all Keycloak Admin REST API calls (client-credentials token fetch,
user-exists check, create-user, assign-role). The client is a plain `@Component`
(`KeycloakAdminClient`) with a `SimpleClientHttpRequestFactory` carrying explicit connect/read
timeouts from `KeycloakAdminProperties` (`native.keycloak-admin.*`). A startup self-check
(`KeycloakAdminConfig`) asserts both timeouts are positive before the application accepts traffic.

Explicitly OUT of scope for this ADR: a full Keycloak Admin SDK, retry logic on the Admin calls
(retries on non-idempotent writes are forbidden by ENGINEERING-STANDARDS §4), and compensating
cleanup for the orphaned-company residual (documented as a follow-up).

## Consequences
- **No new Gradle dependency.** `RestClient` is already available. The dependency footprint stays
  minimal and RESTEasy is never introduced into the stack.
- **Consistent timeout discipline.** `KeycloakAdminProperties` mirrors `JwksClientProperties`
  exactly: `@NotNull`, configurable defaults (`connectTimeout=2s`, `readTimeout=5s`), startup
  self-check via the shared `OutboundClientTimeoutCheck.requirePositive`. No outbound client can
  silently run with an infinite/zero timeout.
- **PII / credential hygiene enforced by structure.** `KeycloakAdminClient` never logs the
  `ownerPassword` (it only receives it at the `createUser` call site and passes it directly in the
  POST body without touching a log statement). The `clientSecret` is hidden behind the
  `@ConfigurationProperties` binding; the form-encoded token request body is built programmatically
  without ever being assigned to a variable named `secret` that could be logged.
- **Accepted residual — orphaned company on Keycloak failure.** If the Keycloak user-create call
  fails after the company row is committed, the company remains as an orphaned tenant. This is the
  correct trade-off: distributed transactions are out of scope, and the email pre-check eliminates
  the common duplicate case cleanly. The residual is logged as a `WARN` with the company id only
  (no PII), and a follow-up cleanup job is the recommended remediation.
- **Enforcement.** The startup self-check (`KeycloakAdminConfig.keycloakAdminClient`) fails fast
  at context refresh on a non-positive timeout. `@Validated` on `KeycloakAdminProperties` fails
  fast on a missing required property. The token cache avoids a round-trip on every sign-up call.
