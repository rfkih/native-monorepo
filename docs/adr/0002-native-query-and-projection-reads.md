# 0002. Repository reads are native queries + projections

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** rifki; backend-engineer; code-reviewer
- **Related:** CLAUDE.md "Conventions" + "Never"; docs/CODE-STRUCTURE.md §3.3; the
  `repositoryQueriesAreNative` ArchUnit rule in every service's `config/LayeredArchitectureTest`

## Context
Spring Data repositories had mixed query styles: JPQL `@Query` methods, derived finders, and a few
native queries. Read paths commonly fetched whole `@Entity` rows (effectively `SELECT *`, including
the `Auditable` bookkeeping columns) just to map a handful of fields to a DTO. We wanted predictable,
narrow database reads, a single querying style, and a pattern an AI agent can apply mechanically
without re-deriving it per repository.

## Decision
We will use **native SQL for every repository `@Query`** (`nativeQuery = true`; no JPQL — enforced by
the `repositoryQueriesAreNative` ArchUnit rule). A **read** path selects only the columns it needs
into a Spring Data **interface projection** (snake_case aliases → camelCase getters) that lives in the
feature's dedicated `<feature>.projection` package — never `SELECT *` of the entity, and never nested
in the repository or mixed into `dto`. The full `@Entity` is loaded only on the **write** path
(inherited `findById`/`save`, which needs the whole aggregate to mutate it); `count`/`exists` return
scalars. Reads of columns backed by a JPA `AttributeConverter` (encrypted PII — salary, NIK, bank
account) stay on the entity path, because a native query returns the raw, still-encrypted value.

## Consequences
- Reads fetch a narrow, explicit column set; tenancy is unchanged (RLS auto-applies on the
  transactional connection, so native queries are scoped identically — no `WHERE company_id`).
- `projection` is a first-class, ArchUnit-enforced layer (`mayOnlyBeAccessedByLayers("Service",
  "Repository")`); the `dto` layer must not depend on it, so projection→DTO mapping happens in the
  service/writer.
- The convention is applied across all services and baked into `service-template`, so clones inherit
  it. The objective half (native, no JPQL) is enforced statically; "no `SELECT *` on a read" is
  enforced by code review (generics erasure makes the return-type check impractical in ArchUnit).
- Pre-merge gate: native SQL correctness (column names, `count(...)`/boolean mapping, `timestamptz →
  Instant` projection coercion, `Limit` on native queries) is only fully verifiable by the
  Testcontainers DB suites against real PostgreSQL — those must be run before merge.
