# Engineering Standards — Native

> Concrete, tool-enforceable standards for every backend service. Read alongside **ARCHITECTURE.md** and **docs/EVENT-CATALOG.md**.
>
> This document assumes — and never repeats — the **9 hard rules in CLAUDE.md** (DB-per-service, no sync calls between business services, outbox-only publishing, `Auditable` on every table, RLS tenant scoping, PII encryption, backward-compatible events, the `Money` type, no hardcoded user-facing strings). Rules below reference them as **HR-1 … HR-9**.
>
> Code-structure and layering standards (controller → service → repository → domain, package-by-feature, ArchUnit layering suite) live in the **sibling layering doc**, not here.
>
> Each rule names its **enforcer** — `ArchUnit` / `test` / `Checkstyle` / `Spotless` / `JaCoCo` / `Sonar` / `review`. A rule with no automated enforcer is a `review` checklist item, not a suggestion.

---

## 0. The competitive bar — Native ≥ blackheart (maintained scorecard)

> **North star — read first.** Native is explicitly benchmarked against the team's other Spring Boot
> system, **`blackheart-trading-engine`** (`C:\Project\blackheart-trading-engine` — a package-by-layer
> modular monolith). Standing rule: **Native must be ≥ blackheart on every dimension below**, and a PR
> may never regress a ✅ to a gap without an ADR recording why. This table is the single source of
> truth for "are we still ahead"; each row points to the section that defines the rule + its enforcer.
> When a gap's code lands, flip its status to ✅ **in the same PR**.

| # | Dimension | Native standard (rule → enforcer) | vs blackheart | Status |
|---|---|---|---|---|
| 1 | Package cohesion | package-by-feature + layering suite — `docs/CODE-STRUCTURE.md` (ArchUnit) | by-layer, 250-file `service/` | ✅ ahead |
| 2 | Structure can't rot | ArchUnit wired into `./gradlew check` | Sonar + discipline | ✅ ahead |
| 3 | Error contract | RFC-7807 `ProblemDetail` — §1.2 (test) | custom `ResponseDto` envelope | ✅ ahead |
| 4 | Type safety | records + `Money` + no-float (ArchUnit); **no Lombok** | Lombok mutable entities | ✅ ahead — *add no-Lombok ArchUnit guard* |
| 5 | Tenant isolation | RLS + no-manual-`company_id`-filter — §2.1–2.2 (ArchUnit) | userId/account ownership checks | ✅ ahead |
| 6 | Test rigor | Testcontainers + ArchUnit + event-contract triad — §3 (test) | unit + Sonar | ✅ ahead |
| 7 | Edge security | gateway JWT + RLS + role-gated routes — §6 (built this session) | JWT filter, single app | ✅ ahead |
| 8 | Exactly-once / idempotency | unique key + conflict re-read + DLT — §3.2/§4 (test) | per-strategy idempotency | ✅ ahead |
| 9 | **API docs (OpenAPI)** | springdoc + `@Operation` contract test — §1.3 (test) | ✅ springdoc 2.6 + `@Operation`/`@Tag` on all controllers | ⚠ **partial — springdoc 3.0.x pilot in finance ([ADR 0004](adr/0004-openapi-docs-springdoc.md)); remaining: `@Operation` coverage + ArchUnit enforcer + fleet rollout** |
| 10 | **Distributed tracing** | OTel context across every hop — §5 (test) | metrics + Grafana dashboards; custom trace-IDs + MDC (no full OTel either) | ⚠ **gap (deferred) → code phase** |
| 11 | **Error observability** | JSON logs → sink + RED + outbox-lag — §5; *(optional DB inbox)* | ✅ DB error-inbox + alerting | ⚠ **partial → code phase** |
| 12 | Client resilience (timeouts) | explicit connect/read timeouts — §4 (startup check) | configured | ⚠ gap → code phase |
| 13 | **Deployed & proven** | CI + Kustomize authored — `deploy/` | ✅ live in prod | ❗ **infra-gated — owner action, not code** |

**Maintenance protocol.** (a) Any PR touching a dimension updates its Status in the same PR. (b) A
✅→gap regression requires an ADR. (c) Add a row when a benchmark gains a capability Native lacks.
(d) Re-benchmark against `blackheart-trading-engine` when its structure materially changes. (e) Row
13 is earned by deploying, not coding — it is the one bar code alone cannot clear.

**Close-the-gap priority (the "then code" phase):** 9 OpenAPI → 12 client timeouts → 10 tracing → 11
error-sink/alerting. Each lands **with its enforcer** — e.g. an ArchUnit rule that every
`@RequestMapping` handler carries an `@Operation`; a startup self-check that every `RestClient`/
`WebClient` has non-null connect+read timeouts — so a closed gap cannot silently re-open.

---

## 1. API & Error Handling

REST is the external edge (HR via gateway/OpenAPI). Internal hops are events, never sync calls (HR-2).

### 1.1 URIs & methods

- **Version in the path.** Every externally-exposed resource lives under `/api/v1` (URI versioning — a fixed path segment, never a header/query param). Probes `/healthz` and `/actuator/**` are unversioned and exempt. Migrate the existing `POST /sales` → `POST /api/v1/sales`. *(ArchUnit: every `@RequestMapping` path matches `^/api/v\d+/` except `..health..` / actuator.)*
- **Plural-noun, kebab-case resources.** `/api/v1/sales`, `/api/v1/menu-items`; sub-resources nest (`/api/v1/orders/{orderId}/lines`). No verbs in the path — the verb is the HTTP method. Path identifiers are UUIDs. *(review)*
- **Status codes are fixed by this table** *(test)*:

  | Situation | Status |
  |---|---|
  | POST creates a resource | `201 Created` + `Location` header |
  | Idempotent POST retry returns the existing resource | `200 OK` (no second side effect, no event) |
  | GET success | `200` |
  | DELETE success | `204` |
  | Bean-validation / malformed body | `400` |
  | Missing/invalid JWT | `401` |
  | Tenant or role denial | `403` |
  | Unknown resource | `404` |
  | Idempotency-Key reuse with a **conflicting** payload | `409` |
  | Unhandled fault | `500` |

  `SaleController` already returns 201-vs-200 correctly via `RecordSaleResult.created()`; the only gap is the **missing `Location` header on 201** — add it.

```java
return result.created()
    ? ResponseEntity.created(URI.create("/api/v1/sales/" + body.id())).body(body)  // 201 + Location
    : ResponseEntity.ok(body);                                                     // 200 idempotent retry
```

### 1.2 Errors are RFC 7807 ProblemDetail

- **Every 4xx/5xx is `application/problem+json`** built from Spring's `ProblemDetail` — never an ad-hoc JSON map. `ApiExceptionHandler` today returns `{status,error,message}` with the wrong content-type; replace it with `ProblemDetail` from a `@RestControllerAdvice extends ResponseEntityExceptionHandler`. Each problem carries: a stable kebab-case `type` URI (`https://errors.nativeapp.id/<slug>`), numeric `status`, `title`, `detail`, `instance` (request path), and a `traceId` property (the OTel trace id). *(test)*
- **Validation failures carry a machine-readable `errors[]`** of `{field, message}` — one entry per violated constraint — not a `';'`-joined string (the current handler concatenates). The React forms map each violation to its field. *(test)*
- **Domain value rejections map to 400, never 500.** An `IllegalArgumentException` (e.g. an unknown ISO-4217 code from `Money.ofMinor`) → `400` with `type` `invalid-currency` / `invalid-argument`. `SaleControllerValidationTest` already pins unknown-currency → 400; preserve it. *(test)*
- **The catch-all `@ExceptionHandler(Exception)` → 500 must NOT leak** the exception message or stack trace to the client (PII/secret risk, HR-6). Log it with the `traceId`; return a generic detail. *(test)*

```java
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail onInvalid(MethodArgumentNotValidException ex, HttpServletRequest req) {
    var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    pd.setType(URI.create("https://errors.nativeapp.id/validation-failed"));
    pd.setTitle("Validation failed");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("traceId", Span.current().getSpanContext().getTraceId());
    pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
        .map(f -> Map.of("field", f.getField(), "message", f.getDefaultMessage())).toList());
    return pd; // Content-Type: application/problem+json is set automatically for ProblemDetail
  }
}
```

- **ProblemDetail text is English server diagnostics, not user copy (HR-9).** The frontend maps the stable `type` URI → an i18n key for display. The backend never localizes problem text by `Accept-Language` and never embeds user-facing strings meant for direct display. *(review)*

### 1.3 DTO boundary, idempotency, pagination, contract

- **No JPA entity crosses a controller signature.** Request/response are records declared at the boundary; a response is built by a static factory (`SaleResponse.from(sale)`). `company_id` and actor are **never** request/response fields — they come from `TenantContext` (HR-5), as `SaleController` already does. *(ArchUnit — also covered by the layering suite.)*

```java
noClasses().that().areAnnotatedWith(RestController.class)
  .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
  .because("controllers expose DTOs, never entities (no lazy-load/PII leaks; tenant from JWT not body)");
```

- **Idempotency is a header, per `(company_id, key)`.** A write that produces a side effect or event **requires** an `Idempotency-Key` HTTP header (move it off the `SaleRequest` body field). A retry with the same key returns the original result (`200`, no second event); a retry with the same key but a **different payload** → `409`. restaurant-service already enforces exactly-once via a `(company_id, idempotency_key)` unique constraint + separate-transaction conflict re-read — only the header promotion and payload-mismatch 409 are new. *(test)*

```java
@PostMapping("/api/v1/sales")
ResponseEntity<SaleResponse> recordSale(
    @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
    @Valid @RequestBody SaleRequest request) { ... }
```

- **Collection GETs are paginated with an envelope** (never a bare array, so fields stay additive): `page` (0-based), `size` (default 20, **max 100**), optional `sort=field,(asc|desc)`. Response: `{ content, page, size, totalElements, totalPages }`. **Sort fields are an explicit per-endpoint allow-list** — reject unknown fields with `400`; never interpolate a raw sort param into a native query (SQL-injection / RLS-bypass risk). `SaleService.findAllForCurrentTenant` is currently unbounded — fix before it ships. *(test)*

```java
record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
// GET /api/v1/sales?page=0&size=20&sort=occurredAt,desc   (size capped at 100; sort field allow-listed)
```

- **OpenAPI 3 is generated, not hand-written.** Each service exposes `/api/v1/openapi.json` (+ Swagger UI in non-prod) via springdoc. Every handler declares an `@Operation` summary and its error responses; the `ProblemDetail` schema is referenced from every 4xx/5xx. A contract test fails the build if a handler lacks a summary or the generated spec stops being a **superset** of the published spec — the API analogue of `SaleRecordedContractTest` for events. *(test)*
- **API evolution is backward-compatible within a major version** (mirrors HR-7 on the REST edge): never remove/rename a response field, tighten a request constraint, or repurpose a status; only add optional request fields and new response fields. A genuinely breaking change ships as `/api/v2`. *(review)*

---

## 2. Persistence & Data

PostgreSQL 16, one DB/schema per service, Flyway-owned schema, JPA mapping validated against it.

### 2.1 Repositories & tenant scoping

- **Repositories are thin Spring Data interfaces** — query/derived methods and `@Query` only. No business logic, no `Money` arithmetic, no event publishing, no cross-aggregate orchestration. That belongs in the service/domain layer (`SaleService`/`SaleWriter`, not `SaleRepository`). *(ArchUnit)*
- **Never hand-write a `WHERE company_id = ?`** (no derived `…ByCompanyId…`, no manual `@Query` tenant predicate) and never call the RLS synchronizer by hand. Tenant scoping comes **solely** from the auto-applied RLS GUC on every `@Transactional` method (`RlsAutoApplyAspect`, HR-5). A hand-rolled filter drifts from RLS and creates false safety. *(ArchUnit)*

```java
public interface SaleRepository extends JpaRepository<Sale, UUID> {
  Optional<Sale> findByIdempotencyKey(String idempotencyKey);   // implicitly RLS-scoped
  @EntityGraph(attributePaths = "lines")
  List<Sale> findByBusinessId(UUID businessId);                 // single fetch join, not N+1
}
```

### 2.2 Schema, audit & RLS

- **Every persistent `@Entity` extends `id.co.nativeapp.tenant.Auditable`** (HR-4) and its Flyway migration declares the six `Auditable` columns **plus** `ENABLE` + `FORCE ROW LEVEL SECURITY` with a policy keyed to `current_setting('app.current_tenant', true)` (HR-5). The **only** exception is a CDC-derived read-model projection, which must **not** extend `Auditable`. *(ArchUnit)*
- **`ddl-auto=validate` always** — never `update`/`create`/`create-drop`, in any environment. Flyway owns DDL; Hibernate only validates the mapping against the migrated schema at boot, catching drift (e.g. `CHAR(3)` currency vs `VARCHAR`) before runtime. A round-trip Testcontainers test proves baseline + mapping agree. *(test)*

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate     # Flyway owns DDL; mapping is only validated
    open-in-view: false      # no lazy loads outside a transaction
    properties:
      hibernate.jdbc.time_zone: UTC
      hibernate.default_batch_fetch_size: 100   # batch lazy associations (N+1 guard)
```

### 2.3 Money at rest (HR-8)

- **Money is two columns** — `amount_minor BIGINT NOT NULL` + `currency CHAR(3) NOT NULL` — via an `@Embeddable` exposing `id.co.nativeapp.money.Money` (`MoneyEmbeddable`). **No** entity field is `float`/`double`/`BigDecimal` for a monetary amount; **no** money column is a floating type. Reconstruct via `Money.ofMinor` / `toMoney`, which re-validates ISO-4217. The `@JdbcTypeCode(CHAR)` keeps `validate` agreeing with the migrated `bpchar`. *(ArchUnit)*

```java
@Embeddable class MoneyEmbeddable {
  @Column(name = "amount_minor", nullable = false) private long amountMinor;
  @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency", nullable = false, length = 3) private String currency;
  static MoneyEmbeddable of(Money m) { return new MoneyEmbeddable(m.amountMinor(), m.currency().getCurrencyCode()); }
  Money toMoney() { return Money.ofMinor(amountMinor, currency.strip()); }
}
```

```java
// ArchUnit: no float/double/BigDecimal money on a persistent @Entity OR @Embeddable
// (the money columns live in MoneyEmbeddable, so @Embeddable MUST be covered too).
noClasses().that().areAnnotatedWith(jakarta.persistence.Entity.class)
    .or().areAnnotatedWith(jakarta.persistence.Embeddable.class)
    .should().dependOnClassesThat().haveFullyQualifiedName("java.math.BigDecimal")
    .because("money is libs/money Money (minor units + currency), never a float/BigDecimal (HR-8)");
```

### 2.4 Migrations, indexes & N+1

- **Flyway migrations are immutable and additive (expand/contract).** Never edit, renumber, or delete an applied migration (Flyway checksums them; an edit breaks every existing DB; `baseline-on-migrate=false` makes an edited baseline fail). A breaking column change is a multi-deploy sequence: add nullable/backfilled column → dual-write → backfill → switch reads → drop old column in a **later** release. *(review)*
- **Index the actual access path.** At minimum a UNIQUE constraint on each idempotency/natural key including `company_id` (e.g. `uq_sale_company_idempotency`), a B-tree per non-PK repository query, and a **partial** index for "unprocessed" scans (`WHERE published_at IS NULL`). *(review)*

```sql
-- V7__add_table_constraint.sql  (new file — never edit an applied migration)
ALTER TABLE sale ADD CONSTRAINT uq_sale_company_idempotency UNIQUE (company_id, idempotency_key);
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL; -- partial
```

- **No EAGER fetching.** Every `@ManyToOne`/`@OneToOne` is `fetch = LAZY` (their default is EAGER — be explicit). Declare the load path at query time with `@EntityGraph` or a JPQL fetch join; never lazy-load inside a loop (`open-in-view=false` makes that throw anyway). Batch with `hibernate.default_batch_fetch_size`. **Any new read path that can N+1 is covered by a Testcontainers test asserting the SQL statement count** (Hibernate `Statistics` / datasource-proxy). *(ArchUnit for EAGER; test for statement count.)*

### 2.5 Concurrency & effective-dating

- **Optimistic locking only.** Rely on `Auditable`'s `@Version long`; add no second `@Version` and take no pessimistic lock (`SELECT … FOR UPDATE` / `@Lock(PESSIMISTIC_*)`) without an ADR. Recover from a concurrent unique-constraint collision in a **separate** `REQUIRES_NEW` transaction and re-read — never re-query the poisoned transaction. This is exactly the `SaleService`/`SaleWriter` `create()` + `findExistingByKey()` pattern. *(ArchUnit for the lock ban.)*
- **Effective-dated rows use the sentinel `DATE '9999-12-31'`** for an open-ended `effective_to` — never NULL (keeps range predicates index-friendly). `effective_from`/`effective_to` are `NOT NULL`; "currently effective" filters `:asOf BETWEEN effective_from AND effective_to`. *(Checkstyle — flag NULL/no-default on these columns.)*

```sql
effective_from DATE NOT NULL,
effective_to   DATE NOT NULL DEFAULT DATE '9999-12-31'
```

- **Writes go through a proxied `@Transactional` service bean** so the tx advisor, RLS aspect, and JPA auditing all engage. **Never self-invoke** a `@Transactional` method (self-invocation bypasses the proxy and silently drops the tenant GUC — the reason `SaleWriter` is a separate bean). Bulk writes use `@Modifying` + `TransactionTemplate`; chunk `IN` clauses with `Lists.partition` (≤ 1000). *(ArchUnit for self-invocation / `@Transactional` placement — see the layering suite.)*

---

## 3. Testing & Quality Gates

ArchUnit, JaCoCo, Checkstyle, and Spotless now run inside `./gradlew check` (the `native.quality` convention, applied to every module via `native.java-conventions`); these rules define what they enforce.

### 3.1 The pyramid

- **Boot the least context that proves the assertion.** Pure JUnit (no Spring) for domain/value logic and Avro contract checks (`MoneyTest`, `SaleRecordedContractTest`); `@WebMvcTest` for controller+advice slices, no DB (`SaleControllerValidationTest`, `HealthControllerTest`); `@SpringBootTest` + Testcontainers **only** for persistence/RLS/outbox/idempotency proofs. Booting heavier than the assertion needs is a finding. *(ArchUnit / review)*
- **Postgres-specific behaviour runs against real PostgreSQL 16 as a non-superuser without BYPASSRLS** (the `app_user` pattern in `PostgresRlsTestBase`) — never H2/HSQLDB/embedded Postgres and never the container's default superuser (it carries implicit BYPASSRLS, which makes every isolation assertion silently pass). Wire the datasource via `@DynamicPropertySource` to that role. *(ArchUnit: `@SpringBootTest` classes extend the RLS base; Checkstyle `IllegalImport` bans embedded-DB / default-role helpers.)*

### 3.2 Mandatory proofs

- **Migration + audit round-trip.** Every service with a Flyway baseline keeps one `@SpringBootTest` (ddl-auto=validate) where Flyway applies, Hibernate validates, and an `Auditable` entity round-trips with `company_id`/`created_by` populated from the `TenantContext` scope (`MigrationAndAuditRoundTripTest`, `RecordSaleAcceptanceTest`). *(test)*
- **Per-event contract triad (HR-7).** Every published/consumed event has a test that (a) parses its `.avsc` from the classpath, (b) round-trips a `GenericRecord` through `AvroSerde.serialize`/`deserialize`, and (c) asserts `isBackwardCompatible(prev, new)` is **true** for the real change **and false** for a deliberate break (new required field, no default). No event ships without all three (`SaleRecordedContractTest`). *(test)*

```java
assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();                       // the real change is safe
assertThat(AvroSerde.isBackwardCompatible(v1, addedRequiredNoDefault)).isFalse();  // a break is caught
byte[] bytes = AvroSerde.serialize(record); assertThat(AvroSerde.deserialize(bytes, schema)).isNotNull();
```

- **Atomicity + idempotency for every money-touching service (HR-3).** One test proves the aggregate row and its outbox row commit-or-roll-back together (throw after the outbox write; assert both tables empty over an admin/BYPASSRLS connection — `RecordSaleAtomicityTest`). One proves a retried command with the same `(company_id, idempotency_key)` yields exactly one row + one outbox event, **including under concurrency** (two threads on a `CyclicBarrier` — `RecordSaleConcurrencyTest`). *(test)*

### 3.3 Test hygiene

- **Deterministic.** No `Thread.sleep`, no wall-clock assertions, no real network/Kafka, no inter-test ordering. Use `CyclicBarrier`/`CountDownLatch` for concurrency, fixed `Instant` literals for time, and reset shared-container state in `@BeforeEach` (`TRUNCATE` over the admin connection, as `PostgresRlsTestBase` does). Awaitility is the only allowed wait primitive. *(Checkstyle — ban `Thread.sleep` in tests.)*
- **Behaviour-named in lowerCamelCase prose** (subject + condition + outcome): `recordingASaleWritesExactlyOneSaleRecordedAndIsIdempotentOnRetry`, `withNoTenantScopeReadsAreEmptyAndWritesFailLoudly`. No `test1`/`shouldWork`/`testFoo`; one behaviour per test. *(Checkstyle — method-name regex.)*
- **No PII or secrets in fixtures, assertions, or logs (HR-6).** No real NIK/salary/bank-account, no real Keycloak tokens. Use synthetic tenant ids (the `1111…`/`2222…` UUIDs) and placeholder actors (`cashier-a@example.co.id`). A log-capturing test must assert PII is **absent**. *(review)*

### 3.4 Formatting, static analysis & coverage

- **Spotless (google-java-format) owns formatting, period.** `./gradlew spotlessCheck` is wired into `check` and fails on any diff; `spotlessApply` fixes. Checkstyle carries **no** whitespace/indent/line-length/import-order/brace-wrap rule — anything the formatter decides. *(Spotless)*
- **Checkstyle is a tiny semantic ruleset** (`maxWarnings=0`, main + test): `UnusedImports`, `RedundantImport`, `AvoidStarImport`, `NeedBraces`, `MissingOverride`, `EqualsHashCode`, `IllegalImport` (no `java.util.logging` / `sun.*` / embedded-DB or default-role test helpers), the test-name regex, the `Thread.sleep` ban, and the effective-dating sentinel check. These catch real defects without overlapping Spotless. *(Checkstyle)*
- **ArchUnit enforces the hard rules as tests** in the normal `test` run (cheaper than catching them in `/code-review`): the layered direction (sibling doc), no float/`BigDecimal` money field (HR-8), publishing only via `OutboxWriter` outside `libs/events` (HR-3), no manual `company_id` filter (HR-5), `@SpringBootTest` extends the RLS base, no `@Retryable` on write services (§4). Add a rule per hard rule as it becomes statically checkable. Pin `com.tngtech.archunit:archunit-junit5` in the shared conventions plugin so every module inherits it. *(ArchUnit)*
- **JaCoCo coverage is report-only today; the floor is the ratchet target** — wired into `check` once measured after the layered refactor, then raised-never-lowered (a lowering needs a PR note): per module **instruction ≥ 0.70, branch ≥ 0.60**; money/tenant/events libs and any money/tenancy/outbox class held to **instruction ≥ 0.85**. Exclude generated Avro types, `*Application`, `*Config`, DTO records (via `classDirectories`). Sonar consumes the JaCoCo XML — one coverage number, not two. *(JaCoCo)*

```kotlin
// A precompiled convention plugin CANNOT declare a plugin version, so put Spotless on the
// build-logic classpath, then apply it by id (no version) in a dedicated quality convention.
// build-logic/build.gradle.kts:
dependencies { implementation("com.diffplug.spotless:spotless-plugin-gradle:7.0.2") } // jacoco/checkstyle are core

// build-logic/src/main/kotlin/native.quality.gradle.kts — applied to EVERY module via native.java-conventions:
plugins { jacoco; checkstyle; id("com.diffplug.spotless") }    // no version here — it comes from the classpath
spotless { java { googleJavaFormat("1.25.2"); target("src/**/*.java"); removeUnusedImports() } }
checkstyle { configFile = rootProject.file("config/checkstyle/checkstyle.xml"); maxWarnings = 0 }
tasks.withType<Test>().configureEach { finalizedBy("jacocoTestReport") }  // coverage report today
tasks.named("check") { dependsOn("spotlessCheck") }                       // Spotless + Checkstyle block

// RATCHET TARGET (deferred until coverage is measured post-refactor): wire the gate into check.
// Exclude non-logic classes via classDirectories path globs (reliable) — not fragile rule `excludes`:
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
  violationRules { rule {
    limit { counter = "INSTRUCTION"; minimum = "0.70".toBigDecimal() }
    limit { counter = "BRANCH";      minimum = "0.60".toBigDecimal() }
  } }
  classDirectories.setFrom(files(classDirectories.files.map {
    fileTree(it) { exclude("**/*Application.*", "**/config/**", "**/*Request.*", "**/*Response.*") }
  }))
}
// once green: tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
```

### 3.5 CI gates, versions & commits

- **Sonar is a hard gate** (`./gradlew sonar`): 0 new blocker/critical issues, no unreviewed new security hotspots, coverage-on-new-code ≥ 80%, 0 duplicated blocks on new code. It fails the pipeline; it is not advisory. *(Sonar)*
- **All dependency versions live in `gradle/libs.versions.toml`** (none today) — no inline version literals in any `build.gradle.kts` except the BOMs in the convention plugins. The Spring Boot BOM governs Spring/Testcontainers/JUnit; the catalog adds only what the BOM doesn't (ArchUnit, google-java-format, plugins). A dependency-hygiene check fails on a banned/duplicate/dynamic (`+`) version; dependency scanning is a hard CI gate. *(Sonar / build)*
- **Conventional Commits + SemVer.** `feat/fix/refactor/test/build/chore/docs(scope)`; a break is `!` or a `BREAKING CHANGE:` footer. An event-schema or public-API break is a MAJOR bump **and** an ADR. A commit-msg hook rejects non-conforming messages. *(review)*

---

## 4. Resilience

- **Every outbound client sets explicit connect + read/response timeouts** (HTTP `RestClient`/`WebClient`, JDBC, gRPC, Kafka, Redis, Keycloak JWKS) via `@ConfigurationProperties` — never a library default (often infinite). A startup self-check fails fast if any registered `RestClient`/`WebClient` has a null connect or read timeout. None are configured today — this is a real gap. *(ArchUnit / startup check)*
- **Retries only for idempotent operations**, with bounded exponential backoff + jitter (Resilience4j `maxAttempts ≤ 4`, `ofExponentialRandomBackoff`). **Non-idempotent writes** (`POST /sales`, any aggregate INSERT) are **never** wrapped in a retry — exactly-once is the `(company_id, idempotency_key)` unique constraint + `SaleService` conflict re-read, not a retried write. *(ArchUnit: no `@Retryable` on a write/`@Service` create path.)*

```java
noClasses().that().areAnnotatedWith(Service.class)
  .should().beAnnotatedWith("org.springframework.retry.annotation.Retryable")
  .because("retries are for idempotent ops only; writes dedupe via the unique constraint");
```

- **Consumers dedupe by event UUID and DLT on poison (HR-3).** Run each handler through `libs/events` `ProcessedEventStore.processOnce(eventId, …)` **inside the same transaction** as its side effects; dedupe by the event's UUID `id`, never by offset (breaks on rebalance/compacted replay). A non-transient failure routes the record to `<topic>.DLT` after a **bounded** retry budget — never an infinite in-place retry that blocks the partition (and stalls finance consolidation). *(test)*

```java
@KafkaListener(topics = "SaleRecorded")
void on(ConsumerRecord<String, byte[]> rec) {
  tx.executeWithoutResult(s -> processedEvents.processOnce(eventIdFrom(rec), () -> ledger.post(rec)));
} // non-transient failure -> DefaultErrorHandler -> SaleRecorded.DLT (bounded retries, no partition block)
```

- **The only sanctioned sync edges are gateway→JWKS and shell-BFF→finance read models (HR-2).** Each is wrapped in a Resilience4j `CircuitBreaker` with a defined fallback (degraded/cached response, or fail-closed) — never an unguarded blocking call. Cross-service business sync calls remain forbidden. *(ArchUnit: business services depend on no other-service client/`WebClient` to a Native host.)*

---

## 5. Observability

- **Structured JSON logs, one object per line**, carrying `service`, `level`, `logger`, `message`, the correlation/request id, and OTel `trace_id` + `span_id` from MDC. The current plain-text console pattern is not machine-parseable by Loki — replace it with a JSON encoder (logstash-logback-encoder) wired in a **shared `logback-spring.xml` in service-template** so every service inherits it identically. *(review)*

```xml
<!-- logback-spring.xml (shared in service-template) — JSON logs with trace correlation -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
  <includeMdcKeyName>trace_id</includeMdcKeyName>
  <includeMdcKeyName>span_id</includeMdcKeyName>
  <includeMdcKeyName>correlation_id</includeMdcKeyName>
  <customFields>{"service":"${spring.application.name}"}</customFields>
</encoder>
```

- **RED metrics via Micrometer/Prometheus**: request Rate + Error rate + Duration on HTTP and on each consumer, plus **outbox lag** (unpublished rows — the early warning that Debezium/relay is behind) and consumer dedupe-skip count. Dotted Micrometer names. **`company_id` is NEVER a metric tag** (thousands of tenants → unbounded cardinality → Prometheus outage) — it stays in logs/traces only. *(test)*
- **Continuous distributed tracing across every hop.** Trace context propagates over Kafka headers (producer stamps W3C `traceparent` into the outbox row's `headers` column; consumer extracts it) and over every sync edge. No handler starts a fresh **root** span for an inbound request/event that already carries context — otherwise operations→event→finance→dashboard latency is unattributable. *(test)*

---

## 6. Security

- **Validate all external input at the edge** with Jakarta Bean Validation (`@Valid` on the controller body); a malformed request → `400` via the shared advice, never a `500`. `company_id` and actor **always** come from the authenticated `TenantContext` (JWT-derived), **never** from the body or a client header in production. The header-trusting `DevTenantFilter` is dev-only and **must** stay `@Profile("dev")`-gated. *(ArchUnit / review)*
- **Secrets only via env/Vault**, referenced through `${ENV}` placeholders or `@ConfigurationProperties` — never a literal secret committed in `application.yml` or source. The app connects as its **own non-superuser, non-BYPASSRLS role** (the least-privilege role RLS depends on, exactly as `PostgresRlsTestBase` provisions `app_user`); a superuser/BYPASSRLS datasource role is forbidden in any non-test profile — it silently disables all tenant isolation. Committed dev defaults are fallbacks, not real secrets. *(Checkstyle `RegexpMultiline` flags a literal `password:`/superuser role.)*

```yaml
# FORBIDDEN in any committed config:
spring.datasource.password: hunter2     # literal secret -> must be ${DB_PASSWORD}/Vault
spring.datasource.username: postgres    # superuser/BYPASSRLS role -> RLS silently off
```

- **No PII or secret reaches a log, metric tag, span attribute, exception message, or outbox header (HR-6).** Outbox headers and event payloads are PII-free (the `OutboxWriter` javadoc already states this); PII columns are field-level encrypted. A logging filter/test asserts known PII field names never appear in emitted output. *(test)*

---

## 7. Configuration

- **Everything externalized (12-factor), bound to `@Validated @ConfigurationProperties`** with Jakarta constraints; the app **fails fast at startup** if any required property is missing or invalid. Spring profiles (`dev`/`test`/`prod`) select environment config — **no profile-conditional business logic in code**. *(test)*
- **No business/domain config hardcoded in Java** — currencies, languages, statutory rates, GL mappings, thresholds are **data / effective-dated rows owned by their service** (statutory rules are versioned effective-dated rows; a company's base currency and default language live on the company per CLAUDE.md, not in code). This is the data-driven-rules principle and reinforces HR-9. *(test / review)*

```java
@Validated
@ConfigurationProperties("native.client.finance")
record FinanceClientProps(
    @NotNull URI baseUrl,
    @NotNull @DurationMin(millis = 1) Duration connectTimeout,
    @NotNull @DurationMin(millis = 1) Duration readTimeout) {}
```

```yaml
# application.yml — explicit client timeouts + readiness aggregating DB & Kafka
resilience4j:
  retry.instances.eventConsumer: { max-attempts: 4, enable-exponential-backoff: true,
                                    exponential-backoff-multiplier: 2, randomized-wait-factor: 0.5 }
management:
  endpoint.health.group.readiness: { include: readinessState, db, kafka }
```

- **Health probes are split and exempt from the tenant filter.** `GET /healthz` is the liveness LB probe — **no tenant header, no DB hit** (a DB-touching liveness flaps on transient blips and gets the pod killed). Readiness aggregates the **DB and Kafka** indicators so a service reports `NOT_READY` (and is pulled from rotation) while its read model is still hydrating or its broker is down — `management.endpoint.health.probes.enabled` is on today but no readiness group binds DB/Kafka. `/healthz` and `/actuator/**` stay exempt from the tenant filter, as `DevTenantFilter.shouldNotFilter` already does. *(test)*

---

## Enforcer index

| Enforcer | What it guards here |
|---|---|
| **ArchUnit** | DTO-at-boundary, `/api/v1` URIs, repo purity & no manual `company_id` filter, `Auditable`+RLS on entities, no float money, no `@Retryable` on writes, no cross-service sync client, `@SpringBootTest` extends RLS base |
| **test** | Status-code table, ProblemDetail shape, idempotency-key 409, pagination envelope/caps, OpenAPI superset, ddl-validate round-trip, event contract triad, atomicity+idempotency, RED metrics, trace continuity, fail-fast config, readiness groups, PII-absence |
| **Checkstyle** | Tiny semantic ruleset, test-name regex, `Thread.sleep` ban, effective-dating sentinel, literal-secret/superuser-role scan |
| **Spotless** | All formatting (google-java-format) |
| **JaCoCo** | Coverage floors (0.70/0.60; 0.85 for money/tenant/outbox), ratchet |
| **Sonar** | Hard CI quality gate + dependency scan, consumes JaCoCo XML |
| **review** | Resource naming, API back-compat, migration immutability, index choices, JSON-log encoder, commits/SemVer, PII-free fixtures |
