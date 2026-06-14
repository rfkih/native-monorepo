package id.co.nativeapp.employee.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized configuration for PII column-level encryption (rule 6 — salary/NIK/bank account are
 * column-level encrypted, never logged).
 *
 * <p><strong>Key sourcing (12-factor / ENGINEERING-STANDARDS §6).</strong> The AES-256 key is bound
 * from {@code native.pii.key} — a base64-encoded 32-byte key. It is supplied as the {@code
 * NATIVE_PII_KEY} environment variable in every real environment; <strong>in production that env
 * value is injected from Vault</strong> (a short-TTL secret reference, never a literal in committed
 * config). A committed dev default exists ONLY so the local dev stack and the test suite have a
 * deterministic round-trip key — it is a fallback, not a real secret (the {@code application.yml}
 * default is a placeholder, and the build's test task overrides it via {@code NATIVE_PII_KEY}).
 *
 * <p>The app <strong>fails fast at startup</strong> if the key is missing/blank (the
 * {@code @Validated} {@code @NotBlank}) or not a valid 32-byte base64 value (validated where the
 * {@link PiiCipher} bean is built), so a misconfigured key can never silently degrade to plaintext
 * storage.
 *
 * <p>The key value is never logged: this record's {@link #toString()} is redacted so an accidental
 * {@code log.info("props={}", props)} cannot leak it.
 */
@Validated
@ConfigurationProperties("native.pii")
public record PiiEncryptionProperties(@NotBlank String key) {

  /**
   * Redacted {@code toString} — the raw key value must NEVER reach a log line, a stack trace, or a
   * diagnostic dump (rule 6). Spring Boot's {@code @ConfigurationProperties} are otherwise eligible
   * to be printed (e.g. the configprops actuator endpoint, a debug log), so we redact here too.
   */
  @Override
  public String toString() {
    return "PiiEncryptionProperties[key=***REDACTED***]";
  }
}
