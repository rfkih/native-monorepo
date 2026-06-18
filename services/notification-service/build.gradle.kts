// notification-service (#22) — the platform notification plane. It OWNS notification +
// delivery_receipt, CONSUMES trigger events (here: ConsolidationClosed from finance), DELIVERS
// the notification through a stubbed transport (a NotificationSender; the real SMTP/push provider
// is a future integration selected by profile/property), and PUBLISHES a DeliveryReceipt event via
// the transactional outbox (rule 3).
//
// A deployable Spring Boot 4 app cloned from the carwash-service / finance-service SHAPE: JPA
// persistence over PostgreSQL with a Flyway baseline, auto-applied RLS tenant isolation (rule 5),
// Auditable audit columns, the transactional outbox + the idempotent-consumer dedupe store, the
// RFC-7807 ProblemDetail error handler, and lean observability. It inherits the platform
// auto-configs (RLS/auditing/JWT/RFC-7807) from libs/tenant + libs/security — NO copied config.
//
// NO money here: a notification carries no monetary amount, so it depends on libs/events +
// libs/tenant + libs/security only (NOT libs/money, NOT libs/entitlement-check).

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
    //   events — AvroSerde (raw-bytes Avro, no Schema Registry serde), OutboxWriter (the
    //            DeliveryReceipt producer), and ProcessedEventStore (the idempotent-consumer
    //            dedupe store keyed by the trigger event's UUID). Exposes Avro (org.apache.avro)
    //            transitively as `api`, so parsing the ConsolidationClosed.avsc (from libs/contracts) and
    //            building the DeliveryReceipt record need no extra dependency or codegen.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value, JpaAuditingConfig,
    //            RlsConnectionInitializer + the transaction synchronizer the auto-RLS aspect drives.
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
    // TenantContext from the verified company_id claim (so RLS engages) — never trusting an inbound
    // X-Company-Id header. In the dev profile it yields to the header-trust DevTenantFilter. Brings
    // spring-boot-starter-oauth2-resource-server + security transitively and contributes the shared
    // RFC-7807 ProblemDetail advice; no per-service security code.
    implementation(project(":libs:security"))

    // Web (/healthz) + JPA persistence. notification-service exposes no business REST edge in this
    // slice (it is event-driven), but keeps the liveness probe every Native service ships.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Kafka consumer. The @KafkaListener on "ConsolidationClosed" reads the raw Avro bytes the
    // producer outbox stored (shipped by Debezium) — deserialized with libs/events AvroSerde against
    // this service's shared libs/contracts schema, NOT a Confluent kafka-avro-serializer / Schema
    // Registry serde. In Spring Boot 4 the Kafka auto-configuration lives in the dedicated
    // spring-boot-starter-kafka module (which bundles spring-kafka).
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Validated @ConfigurationProperties
    // fails fast at startup if the placeholder-recipient config is missing (ENGINEERING-STANDARDS §7).
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
    // auto-RLS + idempotency proofs need the real one), a real Kafka broker (the consume test
    // publishes/awaits Avro bytes on the topic), and Awaitility for the async waits (no Thread.sleep).
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
// needed). Set here (not per-test-class) so no test annotation/assertion changes — exactly how the
// other services do it.
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
}
