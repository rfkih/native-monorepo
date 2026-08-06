# CLAUDE.md — Native Build Context

> Auto-loaded every session. Keep this lean. Test for every line: **would Claude make a mistake without it?** If not, cut it.

## What this is
**Native** — a multi-tenant B2B SaaS: a per-company management console over independently deployable vertical services, a shared platform layer, an event-driven financial consolidation core, and an integrated HR module. **Localized** (English / Bahasa Indonesia, more later) and **multi-currency** (IDR / USD, more later). Microservices architecture, deployed pragmatically — first usable slice ships as 1–2 units, services split out under load.

- **Orientation map (AI agents: read FIRST)** — services, events, tables, ports, "where do I find X",
  common tasks: **docs/PROJECT-MAP.md**. Run/test/debug locally + the gotchas: **docs/RUNBOOK.md**.
  History, design decisions, current status: **docs/DEVLOG.md** (keep it current).
- Architecture reference: **ARCHITECTURE.md** (read before any service or event work).
- Event contracts: **docs/EVENT-CATALOG.md** (read before touching any event).
- Build order and tasks: **CLAUDE-CODE-BUILD-PLAN.md**.
- **Engineering standards** (API/RFC-7807, persistence, testing, resilience, observability, security, config): **docs/ENGINEERING-STANDARDS.md**.
- **Code structure & layering** (controller → service → repository → domain, package-by-feature with layer sub-packages, ArchUnit-enforced): **docs/CODE-STRUCTURE.md** — consult before writing a controller, repository, test, or migration.
- **Decisions (ADRs)**: **docs/adr/** — the append-only log of cross-cutting/hard-to-reverse choices and their *why*. Add one for any such change; read `docs/adr/README.md` first.
- **Repo automation**: project slash commands in **.claude/commands/** — `/new-service`, `/new-feature`, `/new-event`, `/new-migration`, `/native-check`. Prefer them over re-deriving a workflow from prose.

## Stack (pinned — no change without an [ADR](docs/adr/))
- **Java 25** (LTS), **Spring Boot 4.x** (Spring Framework 7), **Gradle** (Kotlin DSL)
- **PostgreSQL 16**, one database/schema **per service**, **Flyway**
- **Kafka** + **Schema Registry** (Avro), transactional **outbox** + **Debezium** CDC
- **Redis** + **Caffeine**
- **Keycloak** (OIDC, RS256 JWT), **Spring Cloud Gateway**
- **gRPC** internal (rare), **REST/OpenAPI** external (**springdoc-openapi 3.0.x** — docs fleet-wide, [ADR 0008](docs/adr/0008-openapi-docs-fleet-rollout.md))
- **OpenTelemetry** (Micrometer Tracing bridge, wired fleet-wide — [ADR 0010](docs/adr/0010-distributed-tracing-otel.md)); at split time: **Linkerd** (mTLS), **Vault**
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
- A company's **country**, **base (functional) currency**, and **default language** are set during company creation (org-service) and stored on the company. On the public signup the currency is **derived from the country** (ID→IDR, else USD — ADR 0025), never chosen. **Country and base currency are immutable** once set. The dashboard reads them; it never offers to toggle them.
- **Per-user language** preference lives on the user profile (a teammate may override the company default).
- A view-only **presentation currency** (seeing the books in another currency) is a separate, optional convenience via finance-service FX — not the base currency.

## Conventions
- Package root: `id.co.example.<service>` (replace `example`).
- **Queries are native + projection.** Every repository `@Query` is a native query (`nativeQuery = true`) — no JPQL; the `repositoryQueriesAreNative` ArchUnit rule fails the build on a JPQL `@Query`. A **read** path selects only the columns it needs into a Spring Data **projection interface** (snake_case aliases → camelCase getters), never `SELECT *` of the entity; the projection lives in the feature's dedicated `<feature>.projection` package (an ArchUnit layer accessed only by service + repository), not nested in the repository nor mixed into `dto`. The full `@Entity` is loaded only on the **write** path (inherited `findById`/`save`, which needs the whole aggregate) and `count`/`exists` return scalars — those are not projections. See `services/restaurant-service` (`SaleRepository` + `sale.projection.SaleView`) and **docs/CODE-STRUCTURE.md §3.3**.
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
- **Write a JPQL `@Query` (must be native), or `SELECT *` on ANY production query (read OR full-entity write-path load — name the columns; enforced fleet-wide by `scripts/check-no-select-star.sh` in CI + the pre-commit hook, which catches `@Query` and raw JdbcTemplate SQL alike). A read path returns a `projection`, not the full entity.**
- **Add a currency or language toggle to the dashboard** — those are set at company creation.
- Build the full platform stack before the first usable slice ships.
