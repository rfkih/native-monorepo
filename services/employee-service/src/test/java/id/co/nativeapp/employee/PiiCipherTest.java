package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.config.PiiCipher;
import id.co.nativeapp.employee.config.PiiEncryptionException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit proof of the AES-256-GCM PII cipher (rule 6) — no Spring context. Proves the
 * round-trip, that ciphertext is not the plaintext, that a fresh random IV per value makes two
 * encryptions of the same plaintext differ, that a tampered ciphertext is rejected (GCM auth tag),
 * and that a malformed key fails fast.
 */
class PiiCipherTest {

  // A deterministic 32-byte (AES-256) key, base64-encoded — test-only, never a real secret.
  // Decodes to the 32 ASCII bytes "native-pii-test-key-0123456789ab".
  private static final String KEY = "bmF0aXZlLXBpaS10ZXN0LWtleS0wMTIzNDU2Nzg5YWI=";

  private final PiiCipher cipher = PiiCipher.fromBase64Key(KEY);

  @Test
  void encryptThenDecryptRoundTripsTheOriginalValue() {
    String plaintext = "3201234567890123"; // a synthetic NIK-shaped value (not real PII)
    String ciphertext = cipher.encryptToString(plaintext);
    assertThat(cipher.decryptFromString(ciphertext)).isEqualTo(plaintext);
  }

  @Test
  void ciphertextIsNotThePlaintext() {
    String plaintext = "1234567890";
    String ciphertext = cipher.encryptToString(plaintext);
    assertThat(ciphertext).isNotEqualTo(plaintext).doesNotContain(plaintext);
  }

  @Test
  void aRandomIvPerValueMakesTwoEncryptionsOfTheSamePlaintextDiffer() {
    String plaintext = "0987654321";
    String first = cipher.encryptToString(plaintext);
    String second = cipher.encryptToString(plaintext);
    // Different ciphertext for the same plaintext -> the column never leaks value equality.
    assertThat(first).isNotEqualTo(second);
    // ...yet both decrypt back to the same value.
    assertThat(cipher.decryptFromString(first)).isEqualTo(plaintext);
    assertThat(cipher.decryptFromString(second)).isEqualTo(plaintext);
  }

  @Test
  void aTamperedCiphertextIsRejectedByTheAuthTag() {
    String ciphertext = cipher.encryptToString("5555444433332222");
    byte[] raw = Base64.getDecoder().decode(ciphertext);
    raw[raw.length - 1] ^= 0x01; // flip a bit in the GCM tag region
    String tampered = Base64.getEncoder().encodeToString(raw);
    assertThatThrownBy(() -> cipher.decryptFromString(tampered))
        .isInstanceOf(PiiEncryptionException.class);
  }

  @Test
  void nullValueMapsToNullBothWays() {
    assertThat(cipher.encryptToString(null)).isNull();
    assertThat(cipher.decryptFromString(null)).isNull();
  }

  @Test
  void aKeyOfTheWrongLengthFailsFast() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // 128-bit, not 256
    assertThatThrownBy(() -> PiiCipher.fromBase64Key(shortKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  void theExceptionMessageNeverLeaksThePlaintextOrKey() {
    // A decrypt failure message must carry no PII / key material (rule 6).
    assertThatThrownBy(() -> cipher.decryptFromString("not-valid-base64-or-too-short"))
        .isInstanceOf(PiiEncryptionException.class)
        .hasMessageNotContaining(KEY);
  }
}
