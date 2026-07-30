// loyalty-service — Phase 4 of the POS-parity program (ADR 0027): the sole owner of the loyalty
// points / gift-card ledger of record, and the ONLY home of member PII (phone, display name —
// column-level encrypted).
//
// A deployable Spring Boot 4 app cloned from barbershop-service's wiring recipe: REST web, JPA
// persistence over PostgreSQL with a Flyway baseline, auto-applied RLS tenant isolation (rule 5),
// Auditable audit columns, the transactional outbox (rule 3), and lean observability. It inherits
// the platform auto-configs (RLS/auditing/JWT/RFC-7807) from libs/tenant + libs/security — NO
// copied config.
//
// DELIBERATE OMISSION vs. barbershop-service: NO libs/entitlement-check dependency — loyalty is
// NOT module-gated this phase (per the Phase-4 task scope), so there is no Redis dependency
// either (entitlement-check is the only consumer of Redis in the vertical wiring recipe).
//
// It is BOTH a producer and a consumer:
//   * PRODUCES LoyaltyBalanceChanged / GiftCardStateChanged / LoyaltyRedemptionFlagged via the
//     transactional outbox, in the same transaction as the member/gift-card ledger mutation.
//   * CONSUMES SaleRecorded (points earn/redeem + gift-card redeem), GiftCardSold (card creation +
//     LOAD), SaleVoided/SaleRefunded (full-reversal of a sale's loyalty facts) — all idempotent via
//     ProcessedEventStore + tenant-bound from the event's company_id (no JWT on the consumer path).

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
    //   money  — the Money value type (integer minor units + ISO-4217 currency); every ledger
    //            value_minor/balance_minor amount is Money-shaped, never a float.
    //   events — OutboxWriter (LoyaltyBalanceChanged/GiftCardStateChanged/LoyaltyRedemptionFlagged),
    //            ProcessedEventStore (the idempotent-consumer dedupe for SaleRecorded/GiftCardSold/
    //            SaleVoided/SaleRefunded), and AvroSerde (raw-bytes Avro, no Schema Registry serde).
    //            Exposes Avro (org.apache.avro) transitively as `api`, so building/parsing the
    //            .avsc needs no codegen.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value, JpaAuditingConfig,
    //            RlsConnectionInitializer + the transaction synchronizer the auto-RLS aspect drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:contracts")) // event Avro schemas: single source of truth
    implementation(project(":libs:tenant"))
    implementation(project(":libs:error-inbox"))

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
    // security code. Also contributes the shared RFC-7807 ProblemDetail advice.
    implementation(project(":libs:security"))

    // NOTE: NO libs/entitlement-check dependency here (unlike carwash/barbershop) — loyalty is not
    // module-gated this phase, so there is also no Redis dependency in this service.

    // Web (/healthz + the /api/v1/loyalty/** member/earn-rule/gift-card surface) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Kafka consumer. The @KafkaListeners on SaleRecorded/GiftCardSold/SaleVoided/SaleRefunded read
    // the raw Avro bytes the producer outboxes stored (shipped by Debezium) — deserialized with
    // libs/events AvroSerde against the SHARED libs/contracts .avsc (SaleRecorded/GiftCardSold are
    // multi-vertical-producer contracts; loyalty-service reads the identical classpath resource
    // every producer/consumer resolves, ADR 0003), NOT a Confluent kafka-avro-serializer / Schema
    // Registry serde. In Spring Boot 4 the Kafka auto-configuration lives in the dedicated
    // spring-boot-starter-kafka module (which bundles spring-kafka).
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Valid on every request body
    // rejects malformed input at the edge with a 400 (via the shared ProblemDetail advice). Spring
    // Boot 4 no longer pulls validation in transitively via starter-web.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // AOP: the auto-RLS @Aspect (from libs/tenant) that sets the tenant GUC on every @Transactional.
    // Spring Boot 4 renamed the AOP starter to spring-boot-starter-aspectj.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Lean observability: actuator endpoints + the Prometheus meter registry. Versions from the BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // OpenAPI docs (ADR 0004 / ADR 0008 — fleet rollout). springdoc generates /v3/api-docs (OpenAPI
    // 3.1) and serves /swagger-ui from the live controllers — no hand-maintained spec to drift. The
    // 3.0.x line is the one built for Spring Boot 4 / Framework 7; the version is catalog-pinned,
    // not in the Boot BOM. In non-dev the docs sit behind the JWT chain and are not gateway-routed.
    implementation(libs.springdoc.starter.webmvc.ui)

    // Schema migrations. Spring Boot 4 moves Flyway auto-configuration into the dedicated
    // spring-boot-starter-flyway (bundling flyway-core + the autoconfigure module);
    // flyway-database-postgresql registers the PostgreSQL dialect.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), the @WebMvcTest slice
    // (Spring Boot 4 splits it into its own module), a real PostgreSQL 16 via Testcontainers as the
    // unprivileged app_user (RLS is a PostgreSQL feature no embedded engine can emulate, and the
    // auto-RLS + idempotency + PII-at-rest proofs need the real one), a real Kafka broker (the
    // consume tests publish/await Avro bytes on the topics), and Awaitility for the async waits (no
    // Thread.sleep). No Redis testcontainer — this service has no Redis dependency.
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
// restaurant-service / carwash-service / barbershop-service / employee-service do it.
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
    // The PII AES-256 key + HMAC-SHA256 key are 12-factor externalized (NEVER a literal in
    // application.yml) — the test task supplies deterministic 32-byte base64 keys so the suite has
    // a reproducible round-trip without touching Vault. Real environments source these from Vault.
    systemProperty("NATIVE_PII_KEY", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
    systemProperty("NATIVE_PII_HMAC_KEY", "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=")
    // Security review W-4: the gift-card code-derivation HMAC key. MUST be the IDENTICAL value in
    // every other service's test task (restaurant/carwash/barbershop-service) — see
    // GiftCardCodeGenerator's class javadoc for why a fleet-wide key match matters.
    systemProperty("NATIVE_GIFTCARD_CODE_KEY", "Z2lmdC1jYXJkLWNvZGUtaG1hYy1rZXktMDEyMzQ1Njc=")
}
