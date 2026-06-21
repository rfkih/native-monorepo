// libs/error-inbox — the SHARED error-inbox + webhook-alerting machinery (ADR 0005 pilot →
// ADR 0009 fleet rollout). A DLT'd money/business event is no longer a silent failure on ANY
// consuming service: the Kafka DLT recoverer records each consume failure into a per-service
// `error_log` ops table (fingerprint-deduped upsert) and fires a milestone-gated webhook alert.
//
// Extracted from the finance pilot so the five service-agnostic pieces live once:
//   ErrorMessageRedactor · ErrorInboxWriter · AlertPayload · AlertWebhookClient · ConsumeErrorRecorder
// plus an auto-configuration that registers them (REQUIRES_NEW tx template + a default Clock).
//
// A LIBRARY (native.spring-conventions, no Spring Boot application plugin). It is depended on ONLY
// by the event-CONSUMING services (finance, carwash, employee, entitlement, notification); the
// stateless gateway and the pure-producer services do not consume it. The service-specific parts —
// the `error_log` Flyway migration and the DLT error-handler wiring — stay in each service.

plugins {
    id("native.spring-conventions")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // The auto-configuration machinery (registered in
    // META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports).
    api("org.springframework.boot:spring-boot-autoconfigure")

    // JdbcTemplate + the REQUIRES_NEW TransactionTemplate behind the error_log upsert, and the
    // @Component/@Bean/@Value wiring. error_log is written via a plain JdbcTemplate upsert (NOT a
    // JPA @Entity) so it survives a rolled-back business transaction (ADR 0005).
    api("org.springframework:spring-jdbc")
    api("org.springframework:spring-tx")
    api("org.springframework:spring-context")

    // RestClient for the fire-and-forget webhook POST. spring-web (not starter-web) so the lib never
    // drags a servlet container into a consumer; every consuming service already runs on starter-web.
    api("org.springframework:spring-web")

    // SLF4J for the WARN-level fail-safe logging (the writer/recorder/webhook all log on failure).
    // BOM-managed; the consuming services bring the logback binding at runtime.
    api("org.slf4j:slf4j-api")

    // The Kafka ConsumerRecord the DLT recoverer hands to ConsumeErrorRecorder, and Jackson for the
    // AlertPayload the RestClient serialises. compileOnly — every consuming service brings both at
    // runtime (spring-kafka + jackson via starter-web); the lib must compile against them.
    compileOnly("org.apache.kafka:kafka-clients")
    compileOnly("com.fasterxml.jackson.core:jackson-databind")

    // Tests: the pure-unit proofs for the redactor, the milestone predicate, and the fail-safe
    // swallow behaviour (mocked JdbcTemplate). No container needed for these.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.apache.kafka:kafka-clients")
}
