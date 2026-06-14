// service-template — the reusable starting point every Native service is cloned
// from (M0.3). A deployable Spring Boot 4 app: REST web, JPA persistence over
// PostgreSQL with a Flyway baseline, auto-applied RLS tenant isolation, audit
// columns, and lean observability (actuator + micrometer).

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
    //   events — OutboxWriter / ProcessedEventStore / AvroSerde plumbing.
    //   tenant — Auditable @MappedSuperclass, TenantContext scoped value,
    //            JpaAuditingConfig, RlsConnectionInitializer + the transaction
    //            synchronizer the auto-RLS aspect drives.
    implementation(project(":libs:money"))
    implementation(project(":libs:events"))
    implementation(project(":libs:tenant"))

    // Web (/healthz controller) + JPA persistence.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // AOP: the auto-RLS @Aspect that sets the tenant GUC on every @Transactional.
    // Spring Boot 4 renamed the AOP starter to spring-boot-starter-aspectj.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Lean observability: actuator endpoints + the Prometheus meter registry that
    // actually backs the exposed /actuator/prometheus scrape endpoint (it pulls in
    // micrometer-core transitively). Version managed by the Spring Boot 4 BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Schema migrations. Spring Boot 4 moves Flyway auto-configuration into the
    // dedicated spring-boot-starter-flyway (bundling flyway-core + the
    // spring-boot-flyway autoconfigure module); flyway-core alone no longer
    // triggers it. flyway-database-postgresql registers the PostgreSQL dialect
    // (required from Flyway 10+).
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests: the Spring Boot test starter (JUnit 5, AssertJ, MockMvc), the
    // @WebMvcTest slice (Spring Boot 4 splits it into its own module), and a real
    // PostgreSQL 16 via Testcontainers — RLS is a PostgreSQL feature no embedded
    // engine can emulate, and the auto-RLS proof needs the genuine one.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
