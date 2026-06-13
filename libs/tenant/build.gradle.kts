plugins {
    id("native.spring-conventions")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but declares the
        // Testcontainers BOM as a transitive import that io.spring.dependency-
        // management does not flatten into version-less coordinates. Import it
        // explicitly (same pinned 2.0.5) so org.testcontainers:* resolve without
        // hardcoding a version of our own.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Provides the JPA @MappedSuperclass / auditing annotations (Auditable),
    // Spring Data JPA auditing (AuditingEntityListener, AuditorAware,
    // @EnableJpaAuditing) and the spring-tx primitives the RLS connection
    // wiring builds on. Versions come from the Spring Boot 4 BOM
    // (see native.spring-conventions), so no explicit versions here.
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    // Audit-column round-trip test: a sample @Entity persisted against an
    // in-memory H2 (PostgreSQL compatibility mode) with JPA auditing active.
    // Spring Boot 4 splits the @DataJpaTest slice into its own module
    // (spring-boot-starter-data-jpa-test), which also pulls in
    // spring-boot-starter-test (JUnit 5, AssertJ, Mockito).
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")

    // RLS isolation proof: a real PostgreSQL 16 via Testcontainers. RLS is a
    // PostgreSQL feature H2 cannot emulate, so the defense-in-depth tenant
    // isolation test must run against the genuine engine. Testcontainers 2.x
    // renamed every artifact to the testcontainers-* prefix; versions come from
    // the testcontainers-bom imported above.
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
