# 0010. Wire distributed tracing (Micrometer Tracing + OpenTelemetry) fleet-wide

- **Status:** Accepted
- **Date:** 2026-06-22
- **Deciders:** rifki + Claude (pairing)
- **Related:** [ENGINEERING-STANDARDS §0 scorecard #10](../ENGINEERING-STANDARDS.md) + [§5](../ENGINEERING-STANDARDS.md),
  [ADR 0005](0005-error-inbox-and-alerting.md) (the shared logback this builds on),
  [CLAUDE.md](../../CLAUDE.md) (stack: OpenTelemetry)

## Context
ENGINEERING-STANDARDS §5 calls for "continuous distributed tracing across every hop" with the OTel
`trace_id`/`span_id` in the JSON-log MDC — but **no tracing was actually wired**. The shared logback
allow-listed `trace_id`/`span_id` MDC keys, yet nothing populated them: every log line carried an
empty `[,]`, and the RFC-7807 advice handlers + the error-inbox read a `trace_id` MDC key that was
always null. The competitive scorecard tracked this as dimension **#10 (distributed tracing): gap**;
blackheart has custom trace-IDs + MDC but no full OTel, so Native was behind until real tracing landed.

CLAUDE.md already names **OpenTelemetry** in the stack, so this is wiring the named tooling, not a new
pin — but the *how* is a real decision on a brand-new (Spring Boot 4.1) modular tracing surface.

## Decision
Adopt **Micrometer Tracing + the OpenTelemetry bridge** fleet-wide, wired from **`libs/observability`**
— the one observability dependency EVERY service (and the gateway edge) already has, so all services
are traced from a single declaration.

1. **Dependency:** `spring-boot-starter-opentelemetry`. Spring Boot 4.0 split tracing out of
   actuator-autoconfigure into per-concern modules; the bare bridge module alone leaves the bridge
   falling back to a **no-op tracer** (spans carry no trace id). The starter brings the OTLP exporter
   that makes Boot build a *real* `SdkTracerProvider`.
2. **MDC keys aligned to Micrometer:** the shared logback now promotes `traceId`/`spanId` (the keys
   Micrometer's OTel bridge writes) instead of the never-populated `trace_id`/`span_id`. Every reader
   — the RFC-7807 advice in each service, libs/security's `ApiExceptionHandler`, and the error-inbox's
   `ConsumeErrorRecorder` — was updated to `MDC.get("traceId")`, so the real trace id now flows into
   error responses' `traceId` property and `error_log.trace_id` (the DB column keeps its snake-case
   name).
3. **Full sampling, OTLP export off by default:** `ObservabilityEnvironmentPostProcessor` (a
   lowest-precedence, overridable default) sets `management.tracing.sampling.probability=1.0`
   (Boot's 0.1 would leave ~90% of log lines without a trace id — Native is low-volume B2B) and
   disables the OTLP metrics push registry (`management.otlp.metrics.export.enabled=false`). The
   OTLP span exporter is absent without a configured endpoint — `OtlpTracingConnectionDetails`
   requires `management.opentelemetry.tracing.export.otlp.endpoint` which is not set, so the exporter
   bean is never created and no collector is contacted. **`management.tracing.export.enabled` must NOT
   be set to `false`**: Spring Boot 4.1 gates the W3C `TextMapPropagator` bean behind
   `@ConditionalOnEnabledTracingExport`; setting that flag false suppresses the propagator, making
   every Kafka consumer start a new root span instead of continuing the producer trace (ADR 0010 #13).
4. **HTTP/W3C propagation** across the single sanctioned sync edge (gateway → service) is Spring Boot's
   automatic instrumentation — an inbound `traceparent` continues the trace; no handler starts a fresh
   root span for a request that already carries context.

## Implemented follow-up: Outbox → Kafka trace continuity (#13)
The cross-service propagation gap described in the original "Deferred" section is now closed:
- **Producer side:** every `OutboxWriter` records `tracer.currentSpan()` as a W3C `traceparent` string
  in the `outbox.traceparent` column (a nullable `VARCHAR(64)` added by `V*__add_outbox_traceparent.sql`
  on all 7 producer DBs). `MicrometerTraceparentSupplier` reads the current span; `TraceparentSupplier.NOOP`
  is used in tests that do not need span context.
- **Debezium connector:** `additional.placement=traceparent:header:traceparent` maps the DB column to a
  Kafka header. All Debezium connector configs updated.
- **Consumer side:** `spring.kafka.listener.observation-enabled=true` (all consumer `application.yml`
  files) + `factory.getContainerProperties().setObservationEnabled(true)` (all custom `KafkaConfig`
  beans). Spring Kafka calls `PropagatingReceiverTracingObservationHandler.onStart()` which calls
  `OtelPropagator.extract()` with the W3C `traceparent` header, creating a child span whose `traceId`
  matches the producer's. The child span is placed in OTel thread-local scope via `openScope()` before
  the listener method runs. Contract test: `TraceContinuityConsumeAcceptanceTest` in finance-service.
- **Outbox-lag metric:** `OutboxLagMetrics` registers a Micrometer gauge `native.outbox.unpublished`
  (label `service`). The gauge is sampled by the Micrometer registry on demand (e.g. per Prometheus
  scrape interval) — there is no fixed 30 s schedule; the COUNT query runs when the registry pulls
  the gauge value.

## Remaining deferred
- **A real OTLP collector + export** (Tempo/Jaeger/Grafana) — infra, ties to scorecard #13.
  Activate by setting `management.opentelemetry.tracing.export.otlp.endpoint` per environment.

## Consequences
- Every log line now correlates to its span (`traceId`/`spanId` populate the JSON logs); the HTTP edge
  propagates W3C context; the RFC-7807 `traceId` and `error_log.trace_id` carry the real id. **Scorecard
  #10 → ahead of blackheart** for the OTel + MDC + HTTP-propagation half (real OTel vs blackheart's
  custom IDs); the Kafka-hop continuity + export remain the deferred follow-ups above.
- **Fleet-wide from one place** (`libs/observability`) — including the gateway, so the edge is the trace
  root. A service overrides any default (sampling, export, endpoint) in its own `application.yml`.
- **Log field rename** (`trace_id`/`span_id` → `traceId`/`spanId`): safe because the old fields were
  never populated, so no dashboard/query depended on them; a log-format drift guard
  (employee's `PiiAbsentFromEncodedJsonLogTest`) was updated to the new allow-list.
- **No collector noise:** the OTLP span exporter is never created without a configured endpoint; no
  connection failure is logged on flush. The OTLP metrics exporter is explicitly disabled.
- **Full Kafka trace continuity:** every consumer span is a child of the producer span. The trace
  survives the outbox → Debezium CDC → Kafka → listener hop end-to-end. `TraceContinuityConsumeAcceptanceTest`
  is the regression guard.
