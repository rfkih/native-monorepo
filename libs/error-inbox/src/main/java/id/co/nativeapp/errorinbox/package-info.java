/**
 * Shared error-inbox + webhook-alerting machinery (ADR 0005 pilot → ADR 0009 fleet rollout). A
 * DLT'd money/business event is recorded into a per-service {@code error_log} ops table
 * (fingerprint- deduped upsert in a {@code REQUIRES_NEW} transaction so it survives the rolled-back
 * business tx) with PII redacted at write time, and a milestone-gated webhook alert is fired with
 * only the redacted message (HR-6).
 *
 * <p>{@link id.co.nativeapp.errorinbox.ErrorInboxAutoConfiguration} registers the beans on any
 * consuming service that depends on this lib (and has a JDBC + Kafka stack). The service supplies
 * the {@code error_log} Flyway migration and wraps its DLT recoverer with {@link
 * id.co.nativeapp.errorinbox.ConsumeErrorRecorder}.
 */
package id.co.nativeapp.errorinbox;
