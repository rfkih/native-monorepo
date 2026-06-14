// libs/entitlement-check — the SHARED entitlement-check library (ARCHITECTURE.md §2,
// entitlement-service: "Ships a shared entitlement-check library used at three gates —
// shell render, event ingestion, billing. Entitlement state is cached in Redis and
// invalidated by its events.").
//
// It exposes a single contract — EntitlementChecker.isEntitled(companyId, moduleKey) —
// backed by a per-company Redis read-through cache. On a cache MISS it falls back to a
// supplied EntitlementLoader: the owning entitlement-service provides a DB-backed loader;
// a vertical consuming the lib provides a read-model/projection loader. The cache is
// INVALIDATED when an EntitlementGranted/Revoked event arrives, via the cache-invalidation
// entry point (EntitlementCache.invalidate) the consuming service calls from its listener.
//
// It is a LIBRARY (no Spring Boot application plugin): native.spring-conventions layers the
// Spring Boot 4 BOM so spring-data-redis resolves without an explicit version. It deliberately
// carries NO JPA / web / Kafka — the cache + the checker contract + the invalidation entry point,
// nothing more.

plugins {
    id("native.spring-conventions")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but does not flatten the Testcontainers
        // BOM into version-less coordinates; import it explicitly (same pinned 2.0.5 the other
        // libs/services use) so org.testcontainers:* resolve without a hardcoded version of our own.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // Spring Data Redis (Lettuce) — the per-company entitlement cache. Exposed as `api` so a
    // consuming service inherits StringRedisTemplate / RedisConnectionFactory without re-declaring
    // it (every consumer of this lib already wires a Redis connection for the cache). Version from
    // the Spring Boot 4 BOM (see native.spring-conventions), so no explicit version here.
    api("org.springframework.boot:spring-boot-starter-data-redis")

    // Tests: JUnit 5 + AssertJ via the Spring Boot test starter, plus a REAL Redis 7 via a plain
    // Testcontainers GenericContainer (redis:7-alpine) from the core Testcontainers module — no
    // separate Redis module coordinate needed (the same pattern the gateway uses for its token
    // bucket). The cache hit/miss/invalidate behaviour is a Redis behaviour an in-JVM map cannot
    // prove, so it runs against the genuine engine. Awaitility for any async wait (no Thread.sleep).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.awaitility:awaitility")
}
