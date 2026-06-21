// libs/observability — the SINGLE source of Native's shared logging configuration.
//
// It ships exactly one classpath resource — `logback-native-json.xml` — plus the
// logstash-logback-encoder it references, and NOTHING else (no Java, no Spring, no
// JPA). Every deployable service (and the stateless gateway, which depends on none
// of the persistence libs) puts this on its classpath and `<include>`s the shared
// file from its own tiny `logback-spring.xml`, so all services emit identical
// one-object-per-line JSON logs from a single definition — no copied appender XML.
// See docs/ENGINEERING-STANDARDS.md §5 (Observability).
//
// Why a dedicated lib rather than service-template or libs/tenant:
//   - service-template is CLONED, so a logback file there is duplicated per service
//     (drift). A library resource is included by reference — one definition, zero copies.
//   - libs/tenant drags in JPA/spring-tx; the gateway is stateless and must not gain a
//     persistence stack just to log as JSON. This lib is deliberately dependency-light so
//     the gateway can depend on it too.

plugins {
    id("native.spring-conventions")
}

dependencies {
    // The JSON encoder the shared logback config wires in (ENGINEERING-STANDARDS §5).
    // 8.x targets Logback 1.5.x (the version the Spring Boot 4.1 BOM brings, 1.5.34) and
    // Java 11+; it is NOT governed by the Spring Boot BOM, so the version is pinned
    // explicitly here. Exposed as `api` so a consumer that depends on this lib gets the
    // encoder on its runtime classpath (logback loads it by class name at startup).
    // It reuses the jackson-databind Spring Boot already manages — no second JSON stack.
    api("net.logstash.logback:logstash-logback-encoder:8.1")

    // Distributed tracing (ADR 0010, ENGINEERING-STANDARDS §5 #10). Spring Boot 4.0 split tracing
    // out of actuator-autoconfigure into per-concern modules; this is the Micrometer-Tracing +
    // OpenTelemetry one — it brings the micrometer-tracing-bridge-otel bridge AND the Spring Boot
    // auto-configuration that actually WIRES it (the Tracer, the OTel SDK TracerProvider, and W3C
    // `traceparent` propagation across every HTTP hop). It populates the `traceId`/`spanId` MDC keys
    // the shared logback config promotes, so every log line correlates to its span. Exposed as `api`
    // so EVERY consumer (the gateway edge included — this lib is the universal observability
    // dependency) is traced from one declaration. Version BOM-managed (Spring Boot 4.1). NO exporter
    // is wired: spans are created and context propagates in-process / across hops, but shipping them
    // to an OTLP collector is an infra-gated, environment-specific follow-up (ADR 0010). The default
    // sample rate is forced to 1.0 by ObservabilityEnvironmentPostProcessor (overridable).
    // The Spring Boot OpenTelemetry STARTER is what makes Boot build a REAL OTel SdkTracerProvider
    // and wire the Micrometer bridge — with only the bare bridge module the bridge falls back to a
    // no-op tracer whose spans carry no trace id. It pulls the OTLP exporters; actual network export
    // is DISABLED by default (ObservabilityEnvironmentPostProcessor sets
    // management.tracing.export.enabled=false AND management.otlp.metrics.export.enabled=false), so
    // NO collector is contacted — the SDK is real, ids populate the MDC + propagate over HTTP, but
    // nothing is shipped until an environment wires a collector and flips those flags (ADR 0010). The
    // starter also brings an OTLP metrics registry; it sits alongside the existing Prometheus scrape
    // registry and, with metrics export off, is inert.
    api("org.springframework.boot:spring-boot-starter-opentelemetry")

    // The shared `kafka` READINESS health indicator (ENGINEERING-STANDARDS §7) +
    // its auto-configuration. Spring Boot 4 ships NO built-in Kafka health
    // contributor, so a service that names `kafka` in its readiness group needs one
    // to exist or the group-membership validator fails startup. This lib supplies a
    // KafkaAdmin-describe-cluster indicator, auto-registered as `kafka` ONLY when
    // both spring-kafka and the actuator health machinery are on the classpath
    // (@ConditionalOnClass) — so the DB-only services (org/restaurant) and the
    // stateless gateway, which depend on this lib purely for logging, get nothing.
    //
    // Both are compileOnly: the Kafka CONSUMER services already bring spring-kafka +
    // actuator at runtime, so the indicator activates there; the non-Kafka modules
    // never see these types, so the @ConditionalOnClass auto-config stays inert. The
    // BOM (native.spring-conventions) governs the versions.
    compileOnly("org.springframework.boot:spring-boot-starter-actuator")
    compileOnly("org.springframework.boot:spring-boot-starter-kafka")

    // Unit test for the indicator's UP/DOWN mapping (a mocked KafkaAdmin) needs the
    // same types on the test classpath.
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
