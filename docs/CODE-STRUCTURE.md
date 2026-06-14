# CODE-STRUCTURE.md — Native Backend Layering Standard

> The definitive **LAYERED** architecture standard for a Native service. The team chose
> **layered** (`controller -> service -> repository -> domain`), **not hexagonal**. This
> document codifies what the existing code already does well and closes the gaps.
> It references — never restates — the 9 hard rules in `CLAUDE.md` and the topology in
> `ARCHITECTURE.md`. Read both first.
>
> Every rule here is meant to be enforced by a tool (**ArchUnit / Checkstyle / Spotless /
> JaCoCo**) or a test. A rule a reviewer has to remember is a rule that erodes.

---

## 1. The layers

One service = one bounded context (`ARCHITECTURE.md` §2). Within it, exactly four layers
plus reserved technical packages. Dependencies point **downward only**:

```
controller  ──>  service  ──>  repository  ──>  domain
   (HTTP)        (logic, tx)      (data port)     (aggregate, invariants, Money)
        \             \                              ^
         \             \____________________________/  service & repository map to/from domain
          \____ dto / mapper (boundary translation) ___/
                         config  (cross-cutting wiring — no layer; depended on by Spring only)
                       messaging (event/outbox/Avro for the feature; lives with the feature)
```

| Layer | Stereotype | Name suffix | May depend on | Must NOT depend on |
|---|---|---|---|---|
| **controller** | `@RestController` / `@ControllerAdvice` | `*Controller` | service, dto, domain (read-only, for mapping) | repository, another controller, `@Transactional`, broker |
| **service** | `@Service`; helpers `@Component` | `*Service` / `*Writer` / `*Reader` | repository, domain, dto, messaging, `libs/*` | controller, web types |
| **repository** | Spring Data interface | `*Repository` | domain, JDK, Spring Data | service, controller, `Money` arithmetic, events |
| **domain** | `@Entity` / `@Embeddable` / value record | (aggregate name) | `jakarta.persistence`, `libs/money`, `libs/tenant` `Auditable`, JDK | controller, service, repository, dto, Spring web/tx |
| **dto** | none (records) | `*Request` / `*Response` / `*Command` / `*Result` | JDK, bean-validation, domain (for mapper factory) | JPA/Spring annotations, broker |
| **config** | `@Configuration` / filter / aspect / advice | `*Config` (+ filter/aspect/advice) | anything it wires | business logic |

These map 1:1 to the current code: `SaleController` (controller), `SaleService` +
`SaleWriter` (service), `SaleRepository` (repository), `Sale` + `MoneyEmbeddable`
(domain), `SaleRequest`/`SaleResponse`/`RecordSaleCommand`/`RecordSaleResult` (dto),
`SaleRecordedSchema` (messaging), the `*Config`/aspect/filter/advice set (config).

---

## 2. Package-by-feature (NOT package-by-layer)

**Rule.** Under the service root `id.co.<org>.<service>` (today `id.co.nativeapp.restaurant`),
each feature/aggregate gets **one package** holding its controller, service, repository,
domain, dto, mapper, and event classes **together**. Cross-feature technical code lives
only in the reserved `config` package (and, for a multi-feature service, a feature-internal
`messaging` sub-package).

**Do NOT** create one global top-level package per layer — no
`id.co.nativeapp.restaurant.controller` holding every controller.

**Why.** A service is one bounded context whose aggregates enforce their own invariants
(`CLAUDE.md`). Keeping an aggregate's controller/service/repo/domain/events cohesive makes
the eventual service split a **package move**, not cross-cutting surgery, and keeps
`service-template` clonable. The restaurant-service is currently one feature (`sale`) in a
flat package, so feature-by-package is the natural, low-churn target.

### Target layout (restaurant-service)

```
id.co.nativeapp.restaurant
  .sale                         // one bounded aggregate = one feature package
      SaleController.java        // @RestController                    (controller)
      SaleRequest.java           // record, @Valid constraints         (dto)  ── split out of SaleController
      SaleResponse.java          // record + static from(Sale)         (dto)  ── split out of SaleController
      RecordSaleCommand.java     // application command record         (dto)
      RecordSaleResult.java      // application result record          (dto)
      SaleService.java           // @Service — orchestration, logic    (service)
      SaleWriter.java            // @Component @Transactional units    (service)
      PostOutboxHook.java        // @Transactional test seam           (service)
      SaleRepository.java        // Spring Data interface              (repository)
      Sale.java                  // @Entity aggregate, owns invariants (domain)
      MoneyEmbeddable.java       // @Embeddable Money value mapping    (domain)
      SaleRecordedSchema.java    // Avro <-> aggregate                 (messaging)
  .config                       // cross-cutting technical wiring (no business logic)
      PersistenceConfig.java  RlsConfig.java  RlsAutoApplyAspect.java
      EventsConfig.java  DevTenantFilter.java  ApiExceptionHandler.java
      HealthController.java
  RestaurantServiceApplication.java   // @SpringBootApplication at the root (component-scan anchor)
```

`service-template` mirrors this with a `widget` feature package and the same `config`
package, so every cloned service inherits the structure.

> `ApiExceptionHandler` and `HealthController` are cross-cutting (not part of any one
> feature) and live in `config`. A feature-specific advice would instead live in its
> feature package.

---

## 3. Layer responsibilities and forbidden dependencies

### 3.1 controller — a thin HTTP adapter

A `*Controller` method does only:
1. accept a `@Valid *Request` (and `@RequestHeader("Idempotency-Key")` for writes);
2. assemble one application `*Command` and call **exactly one** service method;
3. map the result to a `*Response` and set the HTTP status (+ `Location` on `201`).

**Forbidden in a controller:** repository calls, `@Transactional`, `Money` arithmetic,
event construction, tenant resolution beyond reading the bound `TenantContext`, returning
or accepting an `@Entity`. `SaleController` already models this — keep it that way.

### 3.2 service — all business logic, all transaction boundaries

- `@Transactional` may appear **only** on service-layer classes (`*Service` / `*Writer` /
  `*Reader`), never on a controller, repository, entity, or config.
- Because `RlsAutoApplyAspect` sets the tenant GUC around every `@Transactional` method and
  **self-invocation bypasses the Spring proxy**, a unit of work that needs its own / a new
  transaction MUST be a method on a **separate bean** (the `*Writer` pattern), not a private
  method of the orchestrating `*Service`. `SaleService` (not transactional) deliberately
  delegates its `REQUIRES_NEW` create and the conflict re-read to `SaleWriter` precisely so
  the proxy + aspect engage — this is load-bearing for RLS (rule 5) and the outbox (rule 3).
- Optimistic locking only (`Auditable.@Version`); recover a unique-constraint collision in a
  `REQUIRES_NEW` re-read, never by re-querying the poisoned transaction.

### 3.3 repository — a Spring Data interface, nothing more

- A `*Repository` is an interface extending `org.springframework.data.repository.Repository`
  (typically `JpaRepository`) holding only derived queries / `@Query`.
- **No** business logic, `Money` computation, event publishing, or manual `WHERE company_id`
  — RLS auto-applies (rule 5). `SaleRepository`/`WidgetRepository` document this on purpose.
- A repository MUST be injected **only** into the service layer.
- For an association access path, declare it at query time (`@EntityGraph` / fetch join) — no
  EAGER, no lazy-load in a loop (`open-in-view=false` makes a stray lazy load throw).

### 3.4 domain — owns its invariants, framework-light

- An `@Entity`/aggregate validates its construction (`Objects.requireNonNull`, positive/range
  checks, `Money` via the Money type — rule 8) and exposes no public mutation that breaks an
  invariant; a `protected` no-arg constructor exists **only** for JPA. `Sale` already does this.
- A domain class MUST NOT depend on controller / service / repository / dto, nor on
  `org.springframework.web..` / `org.springframework.transaction..`. Allowed:
  `jakarta.persistence`, `libs/money`, `libs/tenant` `Auditable`, JDK.
- Every persistent `@Entity` extends `id.co.nativeapp.tenant.Auditable` (rule 4) and maps to a
  Flyway table with the six Auditable columns + `ENABLE`/`FORCE ROW LEVEL SECURITY` (rule 5).
  CDC-derived read models are the only Auditable exception.

### 3.5 dto + mapper — the boundary translation layer

- Request/response and application command/result types are **immutable Java records** named
  `*Request` / `*Response` / `*Command` / `*Result`; they carry no JPA/Spring annotations
  beyond bean-validation and never embed an `@Entity`.
- Entity↔DTO mapping is **explicit**: a static factory like `SaleResponse.from(Sale)` or a
  `*Mapper` class, at the controller/service boundary — never inside the domain or repository.
- `company_id` and actor are **never** fields on an inbound `*Request`/`*Command`; they come
  from `TenantContext` (rule 5). This keeps the tenant un-spoofable.

### 3.6 messaging — a dedicated, auditable concern

- Avro schema mapping (`*Schema`), outbox wiring (`EventsConfig`), and producer hooks live in
  the feature package (or a `messaging` sub-package).
- A producer writes events **only** through `libs/events` `OutboxWriter`, inside the same
  `@Transactional` unit that mutates the aggregate (rule 3) — never via `KafkaTemplate` or a
  direct broker call. `SaleWriter.create()` writes the `SaleRecorded` outbox row in the same
  transaction as the sale.
- Consumers (`@KafkaListener`) are idempotent (dedupe by event UUID via
  `ProcessedEventStore.processOnce`), live in their own `*Listener`/`*Consumer` class, and
  delegate to a service.
- Adding/changing an event requires: the `.avsc` on the classpath, an entry in
  `docs/EVENT-CATALOG.md`, and a contract test (the `SaleRecordedContractTest` triad: parse +
  round-trip + `isBackwardCompatible` true-for-change / false-for-break). See rule 7.

---

## 4. The DTO-at-the-boundary rule (no `@Entity` on the wire)

No `@Entity` (nor `@Embeddable` persistence type) may cross a controller boundary. Controller
method parameters and return types are DTOs (`*Request` / `*Response` records), never a domain
entity. Return `ResponseEntity<SaleResponse>`, accept `SaleRequest`, never `Sale`.

**Why.** An entity carries `Auditable` columns, `company_id`, lazy associations and (in other
services) encrypted PII fields. Letting it reach the wire risks lazy-load serialization
failures and PII leaks (rule 6) and couples the API contract to the persistence model. The
translation happens in a mapper (`SaleResponse.from(Sale)`). This is machine-checked
(`controllers must not depend on `@Entity` types`).

---

## 5. Enforcement (ArchUnit / Checkstyle / Spotless / JaCoCo)

The layering is enforced by an ArchUnit suite shipped **once** in `service-template`
(`src/test/.../config/LayeredArchitectureTest`, see §6) and inherited by every cloned service.
It runs as part of `./gradlew test`. The ArchUnit JUnit5 dependency is pinned in the
`native.quality` convention plugin so all modules get it without version drift.

The suite asserts (all scoped to `id.co.nativeapp..`):

1. **Layered direction** — `controller -> service -> repository -> domain`, no upward or skip
   edges. Layers defined by name suffix; controllers may be accessed by nothing, services only
   by controllers, repositories only by services.
2. **Controllers must not touch repositories directly** — `*Controller` must not depend on a
   `org.springframework.data.repository.Repository`.
3. **Repositories accessed only from the service layer** — a `Repository` may be accessed only
   by classes ending `Service` / `Writer` / `Reader`.
4. **Repositories must not depend on services/controllers** — a `*Repository` must not depend
   on a `*Service` / `*Controller`.
5. **`@Entity` accessed only by domain/service/repository/mapper** — not from controllers.
6. **Naming matches stereotype** — a `@RestController` is named `*Controller`; a `@Service` is
   named `*Service`; a Spring Data repository interface is named `*Repository`.
7. **No `@Entity` at the controller boundary** — `*Controller` classes must not depend on
   `@Entity` types (DTO-at-the-boundary, §4).
8. **`@Transactional` only in the service layer** — methods/classes annotated `@Transactional`
   reside in `*Service` / `*Writer` / `*Reader`.
9. **No float money** — no `@Entity` **or `@Embeddable`** may depend on `BigDecimal`/`float`/
   `double` (money is `libs/money` `Money` — minor units + currency; rule 8). The money columns
   live in `MoneyEmbeddable`, so the rule must cover `@Embeddable`, not just `@Entity`.

The current code already complies, so the suite is green on day one and locks the invariants
for every future feature and service. Formatting is owned solely by **Spotless +
google-java-format** (`spotlessCheck` wired into `check`); **Checkstyle** carries only a
curated, non-formatting ruleset (unused/star/redundant imports, `IllegalImport`, `NeedBraces`,
`MissingOverride`, `EqualsHashCode`, `StringLiteralEquality`, `FallThrough`, `MissingSwitchDefault`,
`SimplifyBoolean*`); **JaCoCo** produces coverage reports today (report-only) — its soft floor
(instruction ≥ 0.70, branch ≥ 0.60) is ratcheted into `check` once measured, after the layered
refactor lands. See the build-logic `native.quality` convention and `config/checkstyle/checkstyle.xml`.

---

## 6. The ArchUnit suite (drop-in, generic)

Each service drops in **one** test class that points ArchUnit at its base package. The rules
are written generically (by name suffix + stereotype), so the same class works for every
service. Copy `LayeredArchitectureTest` into `<service>/src/test/java/.../config/` and set
`BASE_PACKAGE` to the **service root** (`id.co.nativeapp.restaurant`,
`id.co.nativeapp.servicetemplate`) — never the broad `id.co.nativeapp`, or it would also analyse
the shared libs. It is a plain JUnit 5 class (not the `@AnalyzeClasses` engine), so it needs only
ArchUnit core on the test classpath:

```java
class LayeredArchitectureTest {
  private static final String BASE_PACKAGE = "id.co.nativeapp.restaurant"; // the service root

  @BeforeAll
  static void importClasses() {
    classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE);
  }

  @Test void controllersMustNotDependOnRepositories() { /* rule.check(classes) */ }
  // + service-direction, repository-access, naming-matches-stereotype, @Entity-boundary,
  //   @Transactional-in-service-only, and no-BigDecimal-money rules (the full class ships in
  //   each service's .config test package).
}
```

---

## 7. Refactor checklist (flat -> layered)

1. Create the `<feature>` and `config` packages under the service root.
2. Move each class per the mapping in the kit's `refactorMapping`. **Split** the two nested
   records out of `SaleController` (`SaleRequest`, `SaleResponse`) into their own files in the
   feature package's dto set.
3. Update `package` declarations and imports (Spotless `removeUnusedImports` + IDE handle the
   bulk); the `@SpringBootApplication` class stays at the root so component scan still covers
   both packages.
4. Add `native.quality` to each module and drop in `LayeredArchitectureTest`.
5. Run `./gradlew spotlessApply check` — the suite, Checkstyle, JaCoCo, and existing
   Testcontainers tests must all stay green (the move is behaviour-preserving).

---

## 8. Cross-references

- Tenancy / RLS / Auditable: `CLAUDE.md` rules 4–6; `ARCHITECTURE.md` §3 (Tenant isolation, Audit).
- Outbox / events / schema compat: `CLAUDE.md` rules 3, 7; `ARCHITECTURE.md` §3 (Eventing), §5.
- Money: `CLAUDE.md` rule 8; `ARCHITECTURE.md` §3 (Money & currency).
- API & error handling, persistence, testing, resilience standards: their respective docs.
