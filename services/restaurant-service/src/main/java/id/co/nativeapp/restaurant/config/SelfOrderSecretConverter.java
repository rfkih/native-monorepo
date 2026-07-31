package id.co.nativeapp.restaurant.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * The JPA {@link AttributeConverter} that makes {@code self_order_access.secret_encrypted} (a
 * {@code TEXT} column) ciphertext at rest (Phase 6, ADR 0029). Applied via {@code @Convert(converter
 * = SelfOrderSecretConverter.class)} on the entity's {@code secretPlaintext} field, it encrypts on
 * write ({@link #convertToDatabaseColumn}) and decrypts on read ({@link
 * #convertToEntityAttribute}) so the application works with the plaintext secret while the database
 * row only ever holds AES-256-GCM ciphertext. Mirrors employee-service's {@code
 * PiiAttributeConverter} (a {@code TEXT}-column converter) exactly, just keyed by {@link
 * SelfOrderSecretCipher} instead.
 *
 * <p><strong>Spring-managed converter.</strong> Spring Boot registers a Hibernate {@code
 * BeanContainer} backed by the application context, so a {@code @Component} converter is
 * instantiated by Spring with its {@link SelfOrderSecretCipher} dependency injected (rather than by
 * Hibernate via a no-arg constructor) — that is how the externally-sourced key (Vault/env in prod)
 * reaches the converter without any static/global state.
 */
@Component
@Converter
public class SelfOrderSecretConverter implements AttributeConverter<String, String> {

  private final SelfOrderSecretCipher cipher;

  public SelfOrderSecretConverter(SelfOrderSecretCipher cipher) {
    this.cipher = cipher;
  }

  /** Encrypts the attribute on the way to the database column (plaintext in, ciphertext out). */
  @Override
  public String convertToDatabaseColumn(String attribute) {
    return cipher.encryptToString(attribute);
  }

  /**
   * Decrypts the stored ciphertext on the way back into the entity (ciphertext in, plaintext out).
   */
  @Override
  public String convertToEntityAttribute(String dbData) {
    return cipher.decryptFromString(dbData);
  }
}
