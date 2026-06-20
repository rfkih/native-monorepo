// finance-service (M1.5) — the financial core, a PURELY DOWNSTREAM consumer.
//
// A deployable Spring Boot 4 app cloned from the restaurant-service SHAPE: JPA
// persistence over PostgreSQL with a Flyway baseline, auto-applied RLS tenant
// isolation (rule 5), Auditable audit columns, the RFC-7807 ProblemDetail error
// handler, and lean observability. It calls no other service: it consumes the
// SaleRecorded event from Kafka, posts an append-only ledger_posting, accumulates a
// consolidated-revenue read model, and exposes a tenant-scoped revenue query API.
//
// Single base currency, NO FX yet (M1.5 scope).

plugins {
    id("native.spring-boot-app")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but does not flatten the
        // Testcontainers BOM into version-less coordinates; import it explicitly
        // (same pinned 2.0.5 the libs use) so org.testcontainers:* — including the
        // kafka module the end-to-end consume test stands up — resolve.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Shared platform libraries this service builds on (do not reinvent):
    //   money  — the Money value type (integer minor units + ISO-4217 currency); the
    //            ledger amount and the consolidated total are Money, never a float.
    //   events — AvroSerde (raw-bytes Avro, no Schema Registry serde), OutboxWriter,
    //            and ProcessedEventStore (the idempotent-consumer dedupe store).
    //            Exposes Avro (org.apache.avro) transitively as `api`, so parsing the
    //            SaleRecorded.avsc (from libs/contracts) needs no extra dependency or codegen.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value,
    //            JpaAuditingConfig, RlsConnectionInitializer + the transaction
    //            synchronizer the auto-RLS aspect drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:contracts")) // event Avro schemas: single source of truth
    implementation(project(":libs:tenant"))

    // observability — the SHARED logback-native-json.xml (one-object-per-line JSON logs with the
    // trace/correlation MDC fields + a dev console fallback) this service's logback-spring.xml
    // <include>s, plus the logstash-logback-encoder it references (ENGINEERING-STANDARDS §5). One
    // source of the log format across every service; no copied appender XML.
    implementation(project(":libs:observability"))

    // Defense-in-depth local JWT validation (#16): a shared auto-configuration that, in the
    // non-dev profile, validates the inbound RS256 token against Keycloak's JWKS and binds
    // TenantContext from the verified company_id claim (so RLS engages) — never trusting an
    // inbound X-Company-Id header. In the dev profile it yields to the header-trust DevTenantFilter.
    // Brings spring-boot-starter-oauth2-resource-server + security transitively; no per-service
    // security code.
    implementation(project(":libs:security"))

    // Web (/healthz + GET /api/v1/revenue) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Kafka consumer. The @KafkaListener on "SaleRecorded" reads the raw Avro bytes the
    // producer outbox stored (shipped by Debezium) — we deserialize with libs/events
    // AvroSerde against finance's shared libs/contracts schema, NOT a Confluent
    // kafka-avro-serializer / Schema Registry serde. In Spring Boot 4 the Kafka
    // auto-configuration (KafkaProperties + KafkaAutoConfiguration) lives in the dedicated
    // spring-boot-starter-kafka module, which bundles spring-kafka; spring-kafka alone no
    // longer carries Boot's KafkaProperties. Versions managed by the Boot 4 BOM
    // (spring-kafka 4.1.0 / kafka-clients 4.2.1).
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Valid / constraint
    // binding on the revenue query rejects a malformed `period` at the edge with a 400
    // (see ApiExceptionHandler). Spring Boot 4 no longer pulls validation in
    // transitively via starter-web.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // AOP: the auto-RLS @Aspect that sets the tenant GUC on every @Transactional.
    // Spring Boot 4 renamed the AOP starter to spring-boot-starter-aspectj.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Lean observability: actuator endpoints + the Prometheus meter registry that
    // backs /actuator/prometheus. Versions managed by the Spring Boot 4 BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // OpenAPI docs (ADR 0004 — pilot service). springdoc generates /v3/api-docs (OpenAPI 3.1) and
    // serves /swagger-ui from the live controllers. The version MATTERS: the 3.0.x line is the one
    // built for Spring Boot 4 / Spring Framework 7; the 2.8.x line targets Boot 3 and returns a
    // Base64-mangled /v3/api-docs on Framework 7 (fixed only in the 3.0.x line). NOT in the Boot BOM,
    // so the version is pinned in the catalog. In non-dev the docs sit behind the JWT chain and the
    // gateway does not route /v3/api-docs or /swagger-ui — docs stay dev/in-cluster, not public.
    implementation(libs.springdoc.starter.webmvc.ui)

    // Schema migrations. Spring Boot 4 moves Flyway auto-configuration into the
    // dedicated spring-boot-starter-flyway (bundling flyway-core + the
    // spring-boot-flyway autoconfigure module); flyway-core alone no longer
    // triggers it. flyway-database-postgresql registers the PostgreSQL dialect.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), a real
    // PostgreSQL 16 via Testcontainers (RLS is a PostgreSQL feature no embedded engine
    // can emulate, and the auto-RLS + idempotency proofs need the real one), a real
    // Kafka broker via Testcontainers (the end-to-end consume test publishes Avro bytes
    // to the topic and awaits the ledger posting), and Awaitility for the async wait
    // (no Thread.sleep). Versions managed by the BOMs (awaitility 4.3.0).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")

    // H2 (BOM-managed) backs ONLY the fast, container-free RLS-aspect-wiring guard
    // (RlsAspectWiredTest): TenantRlsAutoConfiguration @Imports JpaAuditingConfig
    // (@EnableJpaAuditing), which needs a non-empty JPA metamodel, so that unit test stands up a
    // throwaway in-memory persistence unit over a trivial test-local entity — no PostgreSQL.
    testImplementation("com.h2database:h2")
}

// libs/security (#16) ships an auto-configuration whose DEFAULT (non-dev) path stands up a JWT
// SecurityFilterChain that validates the inbound RS256 token against Keycloak's JWKS. The existing
// suites bind the tenant directly (TenantContext.callAs) or via MockMvc and never present a real
// Keycloak token, so they run under the `dev` profile — which selects libs/security's permissive
// DevSecurityConfig and leaves the header-trust DevTenantFilter as the tenant source (no Keycloak
// needed). The SECURED non-dev path is proven separately in libs/security against a real Keycloak.
// Set here (not per-test-class) so no existing test annotation/assertion changes.
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
}
