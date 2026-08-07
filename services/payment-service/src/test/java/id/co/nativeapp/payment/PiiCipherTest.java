package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.payment.config.PiiCipher;
import id.co.nativeapp.payment.config.PiiEncryptionException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The credential cipher (rule 6, ADR 0045): AES-256-GCM round-trip, non-deterministic ciphertext
 * (random IV per value), tamper detection, and fail-fast key validation — the properties the
 * Midtrans server-key column's security rests on.
 */
class PiiCipherTest {

  private static final String KEY_B64 =
      Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

  @Test
  void roundTripsAndNeverRepeatsCiphertext() {
    PiiCipher cipher = PiiCipher.fromBase64Key(KEY_B64);
    String plaintext = "SB-Mid-server-abcdefghij1234";
    String first = cipher.encryptToString(plaintext);
    String second = cipher.encryptToString(plaintext);
    assertThat(cipher.decryptFromString(first)).isEqualTo(plaintext);
    assertThat(cipher.decryptFromString(second)).isEqualTo(plaintext);
    // Random IV per value: equal plaintexts must not leak equality through the column.
    assertThat(first).isNotEqualTo(second);
    assertThat(first).doesNotContain(plaintext);
  }

  @Test
  void nullMapsToNull() {
    PiiCipher cipher = PiiCipher.fromBase64Key(KEY_B64);
    assertThat(cipher.encryptToString(null)).isNull();
    assertThat(cipher.decryptFromString(null)).isNull();
  }

  @Test
  void aTamperedCiphertextFailsTheTagCheckInsteadOfReturningGarbage() {
    PiiCipher cipher = PiiCipher.fromBase64Key(KEY_B64);
    byte[] stored = Base64.getDecoder().decode(cipher.encryptToString("secret-value"));
    stored[stored.length - 1] ^= 0x01;
    String tampered = Base64.getEncoder().encodeToString(stored);
    assertThatThrownBy(() -> cipher.decryptFromString(tampered))
        .isInstanceOf(PiiEncryptionException.class);
  }

  @Test
  void aMisconfiguredKeyFailsFastAtConstruction() {
    assertThatThrownBy(() -> PiiCipher.fromBase64Key("dG9vLXNob3J0"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
    assertThatThrownBy(() -> PiiCipher.fromBase64Key("!!!not-base64!!!"))
        .isInstanceOf(IllegalStateException.class);
  }
}
