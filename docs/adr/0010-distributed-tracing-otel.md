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
3. **Full sampling, export off:** `ObservabilityEnvironmentPostProcessor` (a lowest-precedence,
   overridable default) sets `management.tracing.sampling.probability=1.0` (Boot's 0.1 would leave
   ~90% of log lines without a trace id — Native is low-volume B2B) and DISABLES OTLP span+metric
   export (`management.tracing.export.enabled=false`, `management.otlp.metrics.export.enabled=false`)
   so no collector is contacted by default — the SDK is real, ids populate the MDC and propagate over
   HTTP, but nothing is shipped until an environment wires a collector and flips those flags.
4. **HTTP/W3C propagation** across the single sanctioned sync edge (gateway → service) is Spring Boot's
   automatic instrumentation — an inbound `traceparent` continues the trace; no handler starts a fresh
   root span for a request that already carries context.

## Deferred (the infra-gated remainder of #10)
- **Outbox → Kafka trace continuity** (the distinctive cross-service piece): the producer stamping the
  W3C `traceparent` into the outbox row, Debezium mapping it to a Kafka header, and each consumer
  extracting it so the consume span is a child not a root. This needs an **outbox-schema migration on
  every producer DB** + a Debezium connector change (`additional.placement`) + the **live CDC loop** to
  verify end-to-end — the same infra-gated validation the DEVLOG treats as a separate milestone. Until
  then, a trace is continuous within a service and across the HTTP edge, but an event hop starts a new
  trace on the consumer.
- **A real OTLP collector + export** (Tempo/Jaeger/Grafana) — infra, ties to scorecard #13.
- **Kafka consumer-span observation** (`spring.kafka.listener.observation-enabled`) — pairs with the
  outbox propagation above.

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
- **No collector noise:** export is off by default, so a service with no OTLP collector does not log a
  connection failure on every flush.
