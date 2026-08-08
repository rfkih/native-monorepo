package id.co.nativeapp.org.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * The JPA {@link AttributeConverter} that makes a credential/PII string column ciphertext at rest
 * (rule 6). Applied explicitly via {@code @Convert(converter = PiiAttributeConverter.class)} on the
 * {@code device_credential.password_enc} field, it encrypts on write ({@link
 * #convertToDatabaseColumn}) and decrypts on read ({@link #convertToEntityAttribute}) so the
 * application works in plaintext while the database row only ever holds AES-256-GCM ciphertext.
 *
 * <p><strong>Spring-managed converter.</strong> Spring Boot registers a Hibernate {@code
 * BeanContainer} backed by the application context, so a {@code @Component} converter is
 * instantiated by Spring with its {@link PiiCipher} dependency injected (rather than by Hibernate
 * via a no-arg constructor). That is how the externally-sourced key (Vault/env in prod) reaches the
 * converter without any static/global state.
 *
 * <p>Encryption uses a fresh random IV per value (see {@link PiiCipher}), so the column never leaks
 * equality of underlying values, and the converter never logs the plaintext or the ciphertext.
 */
@Component
@Converter
public class PiiAttributeConverter implements AttributeConverter<String, String> {

  private final PiiCipher cipher;

  public PiiAttributeConverter(PiiCipher cipher) {
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
