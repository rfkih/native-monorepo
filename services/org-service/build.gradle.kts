// org-service (M1.2) — the tenant keystone: owner/account, company (legal employer,
// base_currency [immutable] + default_language), and the org tree (org_unit).
//
// A deployable Spring Boot 4 app cloned from the service-template / restaurant-service
// SHAPE: REST web, JPA persistence over PostgreSQL with a Flyway baseline, auto-applied
// RLS tenant isolation (rule 5), Auditable audit columns, the transactional outbox, and
// lean observability. Creating a company bootstraps a new tenant and emits exactly one
// CompanyCreated event.

plugins {
    id("native.spring-boot-app")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but does not flatten the
        // Testcontainers BOM into version-less coordinates; import it explicitly
        // (same pinned 2.0.5 the libs use) so org.testcontainers:* resolve.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Shared platform libraries this service builds on (do not reinvent):
    //   money  — the Money value type (integer minor units + ISO-4217 currency).
    //            org-service holds no monetary amount, but base_currency is an
    //            ISO-4217 code; it is validated via java.util.Currency exactly as
    //            Money does, and money stays on the classpath so the no-float
    //            ArchUnit money rule and the shared currency vocabulary apply.
    //   events — OutboxWriter / ProcessedEventStore / AvroSerde plumbing. Exposes
    //            Avro (org.apache.avro) transitively as `api`, so building the
    //            CompanyCreated GenericRecord needs no extra dependency or codegen.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value,
    //            JpaAuditingConfig, RlsConnectionInitializer + the transaction
    //            synchronizer the auto-RLS aspect drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:tenant"))

    // Web (/healthz + the /api/v1/companies endpoints) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Bean Validation (Jakarta Validation + Hibernate Validator): @Valid on the
    // create-company / create-business bodies rejects malformed input at the edge
    // with a 400 (see ApiExceptionHandler) instead of letting it reach the service.
    // Spring Boot 4 no longer pulls validation in transitively via starter-web.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // AOP: the auto-RLS @Aspect that sets the tenant GUC on every @Transactional.
    // Spring Boot 4 renamed the AOP starter to spring-boot-starter-aspectj.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Lean observability: actuator endpoints + the Prometheus meter registry that
    // backs /actuator/prometheus. Versions managed by the Spring Boot 4 BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Schema migrations. Spring Boot 4 moves Flyway auto-configuration into the
    // dedicated spring-boot-starter-flyway (bundling flyway-core + the
    // spring-boot-flyway autoconfigure module); flyway-core alone no longer
    // triggers it. flyway-database-postgresql registers the PostgreSQL dialect.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), the
    // @WebMvcTest slice (Spring Boot 4 splits it into its own module), and a real
    // PostgreSQL 16 via Testcontainers — RLS is a PostgreSQL feature no embedded
    // engine can emulate, and the auto-RLS + tenant-bootstrap proofs need the real one.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
