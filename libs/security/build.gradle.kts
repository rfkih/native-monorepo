// libs/security (#16, defense-in-depth) — local JWT validation as a SHARED auto-configuration.
//
// ARCHITECTURE.md (Identity): "Every service validates the signature locally (JWKS);
// never trust the gateway alone." Today org/restaurant/finance trust the gateway-supplied
// X-Company-Id header via their @Profile("dev") DevTenantFilter and do NOT validate the JWT
// locally — so a forged X-Company-Id sent straight to a service port (bypassing the gateway)
// would bind an arbitrary tenant and defeat RLS.
//
// This library closes that gap ONCE, for every business service, via a Spring Boot
// auto-configuration (registered in META-INF/spring/...AutoConfiguration.imports). A service
// gets local JWT validation + tenant binding simply by depending on it — NO copied security
// code per service. The NON-dev path validates the inbound RS256 token against Keycloak's JWKS
// and binds TenantContext from the company_id claim (so the RLS auto-apply aspect engages); the
// dev path stays header-trust only (DevTenantFilter), the two mutually exclusive by profile.
//
// It is a LIBRARY, so it applies native.spring-conventions (Spring BOM, NO Spring Boot
// application plugin). The gateway is the edge resource server and is deliberately NOT a
// consumer of this lib.

plugins {
    id("native.spring-conventions")
}

dependencyManagement {
    imports {
        // The Spring Boot BOM pins testcontainers.version but does not flatten the
        // Testcontainers BOM into version-less coordinates; import it explicitly
        // (same pinned 2.0.5 the other libs/services use) so org.testcontainers:*
        // — and the Keycloak Testcontainers module the defense-in-depth proof uses —
        // resolve without a hardcoded version of our own.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // The tenant primitives the bound JWT drives: TenantContext.runAs(company_id, sub) so the
    // RlsAutoApplyAspect each service already wires sets app.current_tenant on the transaction.
    api(project(":libs:tenant"))

    // The single sync security edge replicated INSIDE each service: validate the inbound RS256
    // token against Keycloak's JWKS (spring.security.oauth2.resourceserver.jwt.*). Exposed as
    // `api` so a consuming service inherits Spring Security + the resource-server types without
    // re-declaring them. spring-boot-starter-web is needed for the servlet filter / ProblemDetail
    // types this auto-configuration builds on (every consuming service already has starter-web,
    // but the library must compile against it directly).
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-web")

    // @ConfigurationProperties autoconfigure annotations + the autoconfigure machinery the
    // AutoConfiguration.imports entry registers. Versions from the Spring Boot 4 BOM.
    api("org.springframework.boot:spring-boot-autoconfigure")

    // Bean Validation (Jakarta Validation + Hibernate Validator): the @Validated
    // TenantOptionalProperties uses @Pattern so a malformed native.security.tenant-optional entry
    // fails fast at startup rather than compiling to a wrong matcher. Exposed as `api` since the
    // annotation is on a bound properties type; consuming services already have it transitively.
    api("org.springframework.boot:spring-boot-starter-validation")

    // Tests:
    //  - the Spring Boot test starter (JUnit 5, AssertJ, MockMvc) + Spring Security test support;
    //  - a REAL Keycloak via Testcontainers (issues genuine RS256 tokens against an imported
    //    realm), exactly as the gateway's edge proof does — so signature/issuer/JWKS validation
    //    and the company_id-claim tenant binding are exercised end to end, not mocked;
    //  - a real PostgreSQL 16 via Testcontainers so the RLS-scoped read in proof (c) returns only
    //    the bound tenant's rows (RLS is a PostgreSQL feature no embedded engine can emulate);
    //  - Awaitility for any async wait (no Thread.sleep).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // The proof app's RLS `widget` table is owned by Flyway (ddl-auto=validate only validates the
    // mapping); the Postgres dialect module registers the engine.
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-aspectj")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("com.github.dasniko:testcontainers-keycloak:3.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.postgresql:postgresql")
}
