package id.co.nativeapp.payment.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * The JPA {@link AttributeConverter} that makes a PII string column ciphertext at rest (rule 6),
 * stored as {@code BYTEA} (the Phase-4 loyalty schema's explicit column type for {@code
 * phone_encrypted}/{@code display_name_encrypted} — see {@link PiiCipher}'s class javadoc for why
 * this differs from employee-service's {@code TEXT} column). Applied via {@code @Convert(converter
 * = PiiBytesAttributeConverter.class)} on the {@code phoneEncrypted}/{@code displayNameEncrypted}
 * entity fields, it encrypts on write ({@link #convertToDatabaseColumn}) and decrypts on read
 * ({@link #convertToEntityAttribute}) so the application works in plaintext while the database row
 * only ever holds AES-256-GCM ciphertext bytes.
 *
 * <p><strong>Spring-managed converter.</strong> Spring Boot registers a Hibernate {@code
 * BeanContainer} backed by the application context, so a {@code @Component} converter is
 * instantiated by Spring with its {@link PiiCipher} dependency injected (rather than by Hibernate
 * via a no-arg constructor) — exactly the employee-service {@code PiiAttributeConverter} pattern.
 * That is how the externally-sourced key (Vault/env in prod) reaches the converter without any
 * static/global state.
 *
 * <p>Encryption uses a fresh random IV per value (see {@link PiiCipher}), so the column never leaks
 * equality of underlying values, and the converter never logs the plaintext or the ciphertext.
 */
@Component
@Converter
public class PiiBytesAttributeConverter implements AttributeConverter<String, byte[]> {

  private final PiiCipher cipher;

  public PiiBytesAttributeConverter(PiiCipher cipher) {
    this.cipher = cipher;
  }

  /**
   * Encrypts the attribute on the way to the {@code BYTEA} database column: {@link
   * PiiCipher#encryptToString(String)} produces {@code base64( IV || ciphertext+tag )}; this
   * base64-DECODES that string into the raw bytes {@code BYTEA} stores.
   */
  @Override
  public byte[] convertToDatabaseColumn(String attribute) {
    String encoded = cipher.encryptToString(attribute);
    return encoded == null ? null : Base64.getDecoder().decode(encoded);
  }

  /**
   * Decrypts the stored ciphertext bytes on the way back into the entity: base64-ENCODES the raw
   * {@code BYTEA} bytes back into the string form {@link PiiCipher#decryptFromString(String)}
   * expects, then decrypts.
   */
  @Override
  public String convertToEntityAttribute(byte[] dbData) {
    String encoded = dbData == null ? null : Base64.getEncoder().encodeToString(dbData);
    return cipher.decryptFromString(encoded);
  }
}
