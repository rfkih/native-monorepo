/**
 * Shared observability wiring for Native (ENGINEERING-STANDARDS §5 + §7).
 *
 * <p>This library is the single source of two cross-cutting observability concerns, so every
 * service (and the stateless gateway) inherits them identically with no copied configuration:
 *
 * <ul>
 *   <li><strong>Structured JSON logging.</strong> The classpath resource {@code
 *       logback-native-json.xml} defines one JSON object per line — carrying {@code service},
 *       {@code level}, {@code logger}, {@code message}, the correlation id, and the OpenTelemetry
 *       {@code trace_id}/{@code span_id} from MDC — plus a dev-profile console fallback. Each
 *       service's tiny {@code logback-spring.xml} {@code <include>}s it, so the log format is
 *       defined once. The {@code logstash-logback-encoder} it references is exposed as an {@code
 *       api} dependency.
 *   <li><strong>The {@code kafka} readiness health indicator.</strong> {@link
 *       id.co.nativeapp.observability.KafkaReadinessHealthIndicator}, auto-registered by {@link
 *       id.co.nativeapp.observability.KafkaReadinessHealthAutoConfiguration} ONLY where
 *       spring-kafka + actuator are present, fills the gap left by Spring Boot 4 shipping no
 *       built-in Kafka health contributor — so a Kafka consumer service can list {@code kafka} in
 *       its readiness group and report {@code NOT_READY} while its broker is down.
 * </ul>
 */
package id.co.nativeapp.observability;
