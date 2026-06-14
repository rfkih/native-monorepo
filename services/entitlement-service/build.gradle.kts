// entitlement-service — the platform service that owns which modules each company has,
// and billing lines (ARCHITECTURE.md §2).
//
// Owns:      module_catalog, tenant_entitlement, billing_line.
// Publishes: EntitlementGranted, EntitlementRevoked (via the transactional outbox).
// Consumes:  CompanyCreated (to grant a new company its DEFAULT entitlements).
// Ships:     uses libs/entitlement-check (the shared Redis-cached entitled? gate) and provides
//            the DB-backed loader + seeds/invalidates the cache from its own events.
//
// A deployable Spring Boot 4 app cloned from the finance-service SHAPE: JPA persistence over
// PostgreSQL with a Flyway baseline, a Kafka consumer of CompanyCreated (raw Avro bytes via
// libs/events AvroSerde, NOT a Schema Registry serde), the transactional outbox for its own
// events, Redis for the entitlement cache, and lean observability.
//
// CRITICAL (post-consolidation): it copies NO RlsConfig / RlsAutoApplyAspect / PersistenceConfig /
// ApiExceptionHandler. RLS auto-apply + JPA auditing come from libs/tenant
// (TenantRlsAutoConfiguration); JWT validation + the RFC-7807 ProblemDetail advice from
// libs/security auto-configs. It declares only service-specific config (EventsConfig / KafkaConfig /
// DevTenantFilter), mirroring how finance-service declares its deps after the consolidation.

plugins {
    id("native.spring-boot-app")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but does not flatten the Testcontainers
        // BOM into version-less coordinates; import it explicitly (same pinned 2.0.5 the libs use)
        // so org.testcontainers:* — including the kafka module the consume test stands up — resolve.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Shared platform libraries this service builds on (do not reinvent):
    //   money  — the Money value type (integer minor units + ISO-4217 currency); BillingLine's
    //            amount is Money, never a float.
    //   events — AvroSerde (raw-bytes Avro, no Schema Registry serde), OutboxWriter, and
    //            ProcessedEventStore (the idempotent-consumer dedupe store). Exposes Avro
    //            (org.apache.avro) transitively as `api`, so building the EntitlementGranted/Revoked
    //            GenericRecords and parsing the consumer-copy CompanyCreated.avsc needs no codegen.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value, and (via
    //            TenantRlsAutoConfiguration) the auto-RLS aspect + JPA auditing this service
    //            inherits with NO copied config.
    //   security — defense-in-depth local JWT validation + the RFC-7807 ProblemDetail advice, both
    //            auto-configurations inherited purely by depending on the lib; dev profile yields to
    //            the header-trust DevTenantFilter.
    //   entitlement-check — the shared Redis-cached entitled?(company, module) gate. entitlement-service
    //            supplies the DB-backed loader and seeds/invalidates the cache from its own events.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:tenant"))
    implementation(project(":libs:security"))
    implementation(project(":libs:entitlement-check"))

    // Web (/healthz + the /api/v1/entitlements endpoints) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Kafka consumer. The @KafkaListener on "CompanyCreated" reads the raw Avro bytes the producer
    // outbox stored (shipped by Debezium) — deserialized with libs/events AvroSerde against this
    // service's OWN consumer copy of the schema, NOT a Confluent kafka-avro-serializer / Schema
    // Registry serde. In Spring Boot 4 the Kafka auto-configuration lives in the dedicated
    // spring-boot-starter-kafka module (which bundles spring-kafka).
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Valid on the grant body rejects a
    // malformed moduleKey at the edge with a 400 (via the shared ProblemDetail advice). Spring Boot 4
    // no longer pulls validation in transitively via starter-web.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // AOP: the auto-RLS @Aspect (from libs/tenant) that sets the tenant GUC on every @Transactional.
    // Spring Boot 4 renamed the AOP starter to spring-boot-starter-aspectj.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Lean observability: actuator endpoints + the Prometheus meter registry. Versions from the BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Schema migrations. Spring Boot 4 moves Flyway auto-configuration into the dedicated
    // spring-boot-starter-flyway (bundling flyway-core + the autoconfigure module);
    // flyway-database-postgresql registers the PostgreSQL dialect.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), a real PostgreSQL 16
    // (RLS + auto-RLS + idempotency need the genuine engine, as the unprivileged app_user), a real
    // Kafka broker (the consume test publishes Avro bytes and awaits the grants), a real Redis 7
    // (the entitlement-check cache invalidation proof), and Awaitility for the async waits (no
    // Thread.sleep). Redis is a plain GenericContainer from the core Testcontainers module.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")
}

// libs/security (#16) ships an auto-configuration whose DEFAULT (non-dev) path stands up a JWT
// SecurityFilterChain that validates the inbound RS256 token against Keycloak's JWKS. The suites
// here bind the tenant directly (TenantContext.callAs) or via MockMvc and never present a real
// Keycloak token, so they run under the `dev` profile — which selects libs/security's permissive
// DevSecurityConfig and leaves the header-trust DevTenantFilter as the tenant source (no Keycloak
// needed). Set here (not per-test-class) so no test annotation/assertion changes — exactly how
// finance-service and org-service do it after the consolidation.
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
}
