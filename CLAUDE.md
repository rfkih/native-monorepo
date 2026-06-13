# CLAUDE.md — Native Build Context

> Auto-loaded every session. Keep this lean. Test for every line: **would Claude make a mistake without it?** If not, cut it.

## What this is
**Native** — a multi-tenant B2B SaaS: a per-company management console over independently deployable vertical services, a shared platform layer, an event-driven financial consolidation core, and an integrated HR module. **Localized** (English / Bahasa Indonesia, more later) and **multi-currency** (IDR / USD, more later). Microservices architecture, deployed pragmatically — first usable slice ships as 1–2 units, services split out under load.

- Architecture reference: **ARCHITECTURE.md** (read before any service or event work).
- Event contracts: **docs/EVENT-CATALOG.md** (read before touching any event).
- Build order and tasks: **CLAUDE-CODE-BUILD-PLAN.md**.

## Stack (pinned — no change without an ADR)
- **Java 25** (LTS), **Spring Boot 4.x** (Spring Framework 7), **Gradle** (Kotlin DSL)
- **PostgreSQL 16**, one database/schema **per service**, **Flyway**
- **Kafka** + **Schema Registry** (Avro), transactional **outbox** + **Debezium** CDC
- **Redis** + **Caffeine**
- **Keycloak** (OIDC, RS256 JWT), **Spring Cloud Gateway**
- **gRPC** internal (rare), **REST/OpenAPI** external
- **OpenTelemetry**; at split time: **Linkerd** (mTLS), **Vault**
- Frontend: **Vite + React + TypeScript + Tailwind + shadcn/ui + TanStack Query + Recharts**; **react-i18next** for localization

## Hard architecture rules — NEVER violate
1. **Database-per-service.** No shared DB. No cross-service joins, ever.
2. **No synchronous calls between business services.** Communicate only via events + locally cached read models. The single sync edge is the gateway validating a JWT.
3. **All event publishing via the transactional outbox.** All consumers idempotent.
4. **Every table extends `Auditable`** (`created_at/by`, `updated_at/by`, `version`, `company_id`). Full history via Debezium CDC — do not hand-roll per-table audit, do not audit derived read models. Money changes also write the hash-chained immutable log.
5. **Every query tenant-scoped by `company_id` AND enforced by Postgres RLS.** Never bypass. Propagate tenant via scoped values, not ThreadLocal.
6. **PII** (salary, NIK, bank account) column-level encrypted, never logged.
7. **Event schema changes backward-compatible only.** Add every new event to the catalog with its Avro schema.
8. **Money is an integer in minor units + an ISO-4217 currency code (a Money type). NEVER a float.** Every monetary amount carries its currency; each posting stores its transaction currency.
9. **No hardcoded user-facing strings.** All UI copy goes through i18n keys (react-i18next). Format every number, date, and currency via locale-aware `Intl` — never manual concatenation.

## Settings live at creation, not in the dashboard
- A company's **base (functional) currency** and **default language** are set during company creation (org-service) and stored on the company. **Base currency is immutable** once transactions exist. The dashboard reads them; it never offers to toggle them.
- **Per-user language** preference lives on the user profile (a teammate may override the company default).
- A view-only **presentation currency** (seeing the books in another currency) is a separate, optional convenience via finance-service FX — not the base currency.

## Conventions
- Package root: `id.co.example.<service>` (replace `example`).
- Native-query aliases snake_case; map via projection interfaces.
- Writes: `@Modifying` + `TransactionTemplate`. Chunk `IN` clauses with `Lists.partition` (≤ 1000).
- One bounded context per service; aggregates enforce their own invariants.
- Effective-dated rows: far-future sentinel `9999-12-31` for open-ended `effective_to`, not NULL.

## Commands
Build `./gradlew build` · Test `./gradlew test` · Integration `./gradlew integrationTest` · Quality gate `./gradlew sonar` · Local stack `docker compose -f docker/compose.dev.yml up`

## How to work — every task
1. **Plan mode first** (research code + ARCHITECTURE.md + event catalog; produce a plan; wait for approval).
2. Work from a **failing test** / acceptance criteria.
3. Implement only the task's scope.
4. Verify: tests + sonar green; contract tests for any event change.
5. Run **`/code-review` in a fresh context**; fix findings. Mandatory for money, tenancy, auth.
6. Commit small, atomic — one task per commit.

## Never
- Touch another service's DB or call a business service synchronously.
- Introduce an event without adding it to `docs/EVENT-CATALOG.md` + a registered Avro schema.
- **Store money as a float. Hardcode a user-facing string.** Log PII. Bypass RLS. Publish outside the outbox.
- **Add a currency or language toggle to the dashboard** — those are set at company creation.
- Build the full platform stack before the first usable slice ships.
