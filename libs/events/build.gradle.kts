plugins {
    id("native.spring-conventions")
}

dependencyManagement {
    imports {
        // Testcontainers 2.x renamed artifacts to the testcontainers-* prefix; the version is
        // pinned by the Spring Boot 4 BOM, surfaced via this explicit BOM import (the same
        // approach libs/tenant uses).
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Reliable-publishing plumbing writes the outbox row inside the caller's
    // transaction via JdbcTemplate. spring-jdbc + spring-tx versions come from
    // the Spring Boot 4 BOM (see native.spring-conventions), so no explicit version.
    api("org.springframework:spring-jdbc")
    api("org.springframework:spring-tx")

    // Avro serde + schema-compatibility checking. Avro is NOT managed by the
    // Spring BOM, so the version is pinned explicitly (stack-approved 1.12.1).
    api("org.apache.avro:avro:1.12.1")

    // Fast plumbing tests (outbox writer, Avro serde, happy-path idempotency) run on H2 in
    // PostgreSQL compatibility mode so the JDBC plumbing is exercised for real.
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework:spring-jdbc")

    // The idempotency claim uses ON CONFLICT DO NOTHING — a guarantee that a duplicate delivery
    // does NOT abort the caller's transaction. That is a PostgreSQL behaviour H2 cannot prove,
    // so it is verified against a real PostgreSQL 16 via Testcontainers (Docker required).
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
