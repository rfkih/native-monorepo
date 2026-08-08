// employee-service (#19) — the HR module's canonical employee, RECORDS ONLY (no
// payroll/compensation yet): employee (+ ptkp_status), employment_contract
// (+ employment_type, legal_employer_id, effective-dated), assignment (org_unit,
// reporting_to, role, effective-dated). Org/team/manager live on the ASSIGNMENT,
// never the employee; an employee holds multiple CONCURRENT assignments that MUST
// all resolve to the SAME legal_employer.
//
// A deployable Spring Boot 4 app cloned from the org-service / finance-service SHAPE:
// REST web, JPA persistence over PostgreSQL with a Flyway baseline, auto-applied RLS
// tenant isolation (rule 5), Auditable audit columns, the transactional outbox
// (rule 3), and lean observability. It inherits the platform auto-configs
// (RLS/auditing/JWT/RFC-7807) from libs/tenant + libs/security — no copied config.
//
// It is BOTH a producer and a consumer:
//   * PRODUCES EmployeeChanged / AssignmentChanged via the transactional outbox (Avro,
//     no PII in the payload);
//   * CONSUMES OrgUnitCreated / OrgUnitChanged into a LOCAL org read model
//     (org_unit_id -> {company_id, legal_employer_id, type, active}), the projection the
//     same-legal-employer invariant is checked against — idempotent via ProcessedEventStore.
//
// PII (NIK + bank account) is column-level encrypted at rest via a JPA AttributeConverter
// (AES-256-GCM, random IV per value), key from externalized config; never logged, never in
// an event payload, masked in responses (rule 6).

plugins {
    id("native.spring-boot-app")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but does not flatten the
        // Testcontainers BOM into version-less coordinates; import it explicitly
        // (same pinned 2.0.5 the libs use) so org.testcontainers:* — including the
        // kafka module the OrgUnit-consume + AssignmentChanged tests stand up — resolve.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Shared platform libraries this service builds on (do not reinvent):
    //   money  — the Money value type. employee-service holds no monetary amount in this
    //            RECORDS-ONLY slice (payroll/compensation is deferred), but money stays on
    //            the classpath so the no-float ArchUnit money rule and the shared currency
    //            vocabulary apply uniformly across services.
    //   events — OutboxWriter (EmployeeChanged/AssignmentChanged), ProcessedEventStore (the
    //            idempotent-consumer dedupe for OrgUnitCreated/Changed), and AvroSerde
    //            (raw-bytes Avro, no Schema Registry serde). Exposes Avro (org.apache.avro)
    //            transitively as `api`, so building/parsing the .avsc needs no codegen.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value, JpaAuditingConfig,
    //            RlsConnectionInitializer + the transaction synchronizer the auto-RLS aspect
    //            drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:contracts")) // event Avro schemas: single source of truth
    implementation(project(":libs:tenant"))
    implementation(project(":libs:error-inbox")) // ADR 0009: cross-service DLT error recorder
    // Object storage (ADR 0048): expense-receipt payloads live in MinIO as content-addressed
    // objects; the expense_receipt row keeps metadata only. Credentials are policy-scoped to
    // the employee/ prefix; also supplies the fleet-shared magic-byte image validator.
    implementation(project(":libs:media-storage"))

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

    // Web (/healthz + the /api/v1/employees endpoints) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Kafka consumer. The @KafkaListener on "OrgUnitCreated"/"OrgUnitChanged" reads the raw
    // Avro bytes the org-service outbox stored (shipped by Debezium) — we deserialize with
    // libs/events AvroSerde against employee's shared libs/contracts schemas, NOT a
    // Confluent kafka-avro-serializer / Schema Registry serde. In Spring Boot 4 the Kafka
    // auto-configuration (KafkaProperties + KafkaAutoConfiguration) lives in the dedicated
    // spring-boot-starter-kafka module, which bundles spring-kafka.
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Valid / constraint
    // binding on the create-employee / add-contract / add-assignment bodies rejects malformed
    // input at the edge with a 400 (see the shared ApiExceptionHandler). Spring Boot 4 no
    // longer pulls validation in transitively via starter-web.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // AOP: the auto-RLS @Aspect that sets the tenant GUC on every @Transactional.
    // Spring Boot 4 renamed the AOP starter to spring-boot-starter-aspectj.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Lean observability: actuator endpoints + the Prometheus meter registry that
    // backs /actuator/prometheus. Versions managed by the Spring Boot 4 BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // OpenAPI docs (ADR 0004 / ADR 0008 — fleet rollout). springdoc generates /v3/api-docs (OpenAPI
    // 3.1) and serves /swagger-ui from the live controllers — no hand-maintained spec to drift. The
    // 3.0.x line is the one built for Spring Boot 4 / Framework 7 (2.8.x targets Boot 3 and returns a
    // Base64-mangled /v3/api-docs on Framework 7); the version is catalog-pinned, not in the Boot BOM.
    // In non-dev the docs sit behind the JWT chain and are not gateway-routed (dev/in-cluster only).
    implementation(libs.springdoc.starter.webmvc.ui)

    // Schema migrations. Spring Boot 4 moves Flyway auto-configuration into the
    // dedicated spring-boot-starter-flyway (bundling flyway-core + the
    // spring-boot-flyway autoconfigure module). flyway-database-postgresql registers
    // the PostgreSQL dialect.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), the @WebMvcTest slice
    // (Spring Boot 4 splits it into its own module), a real PostgreSQL 16 via Testcontainers
    // (RLS is a PostgreSQL feature no embedded engine can emulate, and the auto-RLS + PII
    // ciphertext-at-rest proofs need the real one), a real Kafka broker via Testcontainers
    // (the OrgUnit-consume + AssignmentChanged tests publish/await Avro bytes on the topic),
    // and Awaitility for the async wait (no Thread.sleep). Versions managed by the BOMs.
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
// SecurityFilterChain that validates the inbound RS256 token against Keycloak's JWKS. The suites
// here bind the tenant directly (TenantContext.callAs) or via MockMvc and never present a real
// Keycloak token, so they run under the `dev` profile — which selects libs/security's permissive
// DevSecurityConfig and leaves the header-trust DevTenantFilter as the tenant source (no Keycloak
// needed). Set here (not per-test-class) so no test annotation/assertion changes.
//
// NATIVE_PII_KEY: the test PII encryption key (a base64 32-byte AES-256 key). In production the key
// comes from Vault via env; here a fixed dev/test key keeps the encrypt/decrypt round-trip and the
// ciphertext-at-rest assertions deterministic. It is NOT a real secret.
tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "dev")
    systemProperty(
        "NATIVE_PII_KEY",
        // A deterministic 32-byte (AES-256) key, base64-encoded — test-only, never a real secret.
        // Decodes to the 32 ASCII bytes "native-pii-test-key-0123456789ab".
        "bmF0aXZlLXBpaS10ZXN0LWtleS0wMTIzNDU2Nzg5YWI=",
    )
}
