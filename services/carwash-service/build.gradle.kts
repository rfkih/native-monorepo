// carwash-service (#20) — the 2nd vertical. System of record for car-wash operations
// (wash / bay / upsell), and the second SaleRecorded producer so finance-service consolidates
// carwash revenue alongside restaurant.
//
// A deployable Spring Boot 4 app cloned from the restaurant-service / employee-service SHAPE:
// REST web, JPA persistence over PostgreSQL with a Flyway baseline, auto-applied RLS tenant
// isolation (rule 5), Auditable audit columns, the transactional outbox (rule 3), and lean
// observability. It inherits the platform auto-configs (RLS/auditing/JWT/RFC-7807) from
// libs/tenant + libs/security — NO copied config.
//
// It is BOTH a producer and a consumer:
//   * PRODUCES SaleRecorded (the SAME Avro contract finance already consumes — a consumer-copy
//     SaleRecorded.avsc matching the producer; business_id = the carwash outlet) and
//     MetricPublished (wash_count / upsell_amount at the declared grains), both via the
//     transactional outbox in one @Transactional unit with the wash insert.
//   * CONSUMES EntitlementGranted / EntitlementRevoked into a LOCAL entitlement projection that
//     backs a DB-backed EntitlementLoader for the shared libs/entitlement-check gate (record-wash
//     is REJECTED with 403 when the company is not entitled to the "carwash" module), and the
//     EmployeeChanged / AssignmentChanged events into a LOCAL staff read model — all idempotent
//     via ProcessedEventStore + tenant-bound.

plugins {
    id("native.spring-boot-app")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but does not flatten the Testcontainers
        // BOM into version-less coordinates; import it explicitly (same pinned 2.0.5 the libs use)
        // so org.testcontainers:* — including the kafka module the consume tests stand up — resolve.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Shared platform libraries this service builds on (do not reinvent):
    //   money  — the Money value type (integer minor units + ISO-4217 currency); the wash amount
    //            and the MetricPublished upsell_amount value are Money, never a float.
    //   events — OutboxWriter (SaleRecorded / MetricPublished), ProcessedEventStore (the
    //            idempotent-consumer dedupe for EntitlementGranted/Revoked + EmployeeChanged/
    //            AssignmentChanged), and AvroSerde (raw-bytes Avro, no Schema Registry serde).
    //            Exposes Avro (org.apache.avro) transitively as `api`, so building/parsing the
    //            .avsc needs no codegen.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value, JpaAuditingConfig,
    //            RlsConnectionInitializer + the transaction synchronizer the auto-RLS aspect drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:tenant"))

    // Defense-in-depth local JWT validation (#16): a shared auto-configuration that, in the
    // non-dev profile, validates the inbound RS256 token against Keycloak's JWKS and binds
    // TenantContext from the verified company_id claim (so RLS engages) — never trusting an
    // inbound X-Company-Id header. In the dev profile it yields to the header-trust DevTenantFilter.
    // Brings spring-boot-starter-oauth2-resource-server + security transitively; no per-service
    // security code. Also contributes the shared RFC-7807 ProblemDetail advice.
    implementation(project(":libs:security"))

    // The shared Redis-cached entitled?(company, module) gate. carwash-service supplies the
    // DB-backed loader over its LOCAL entitlement projection (kept current by EntitlementGranted/
    // Revoked) and INVALIDATES the cache from those same events — the "real gating in the verticals"
    // (Phase-2). Pulls spring-data-redis (Lettuce) transitively as `api`.
    implementation(project(":libs:entitlement-check"))

    // Web (/healthz + POST /api/v1/washes) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Kafka consumer. The @KafkaListeners on EntitlementGranted/EntitlementRevoked +
    // EmployeeChanged/AssignmentChanged read the raw Avro bytes the producer outboxes stored
    // (shipped by Debezium) — deserialized with libs/events AvroSerde against this service's OWN
    // consumer copies of the schemas, NOT a Confluent kafka-avro-serializer / Schema Registry serde.
    // In Spring Boot 4 the Kafka auto-configuration lives in the dedicated spring-boot-starter-kafka
    // module (which bundles spring-kafka).
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Valid on the POST /api/v1/washes
    // body rejects malformed input at the edge with a 400 (via the shared ProblemDetail advice).
    // Spring Boot 4 no longer pulls validation in transitively via starter-web.
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

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), the @WebMvcTest slice
    // (Spring Boot 4 splits it into its own module), a real PostgreSQL 16 via Testcontainers as the
    // unprivileged app_user (RLS is a PostgreSQL feature no embedded engine can emulate, and the
    // auto-RLS + idempotency proofs need the real one), a real Kafka broker (the consume tests
    // publish/await Avro bytes on the topics), a real Redis 7 (the entitlement-check cache gate),
    // and Awaitility for the async waits (no Thread.sleep). Redis is a plain GenericContainer from
    // the core Testcontainers module.
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
// restaurant-service / entitlement-service / employee-service do it after the consolidation.
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
}
