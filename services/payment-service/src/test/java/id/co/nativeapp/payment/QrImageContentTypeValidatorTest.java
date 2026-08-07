package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.payment.settings.domain.InvalidQrImageException;
import id.co.nativeapp.payment.settings.domain.QrImageContentTypeValidator;
import org.junit.jupiter.api.Test;

/**
 * The magic-byte whitelist for static-QRIS uploads (ADR 0045): the declared multipart header is
 * untrusted — the ACTUAL bytes decide, and the canonical detected constant is what gets stored.
 */
class QrImageContentTypeValidatorTest {

  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};
  private static final byte[] WEBP = {
    'R', 'I', 'F', 'F', 0x10, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '
  };

  @Test
  void detectsTheThreeWhitelistedFormats() {
    assertThat(QrImageContentTypeValidator.detect(PNG)).isEqualTo("image/png");
    assertThat(QrImageContentTypeValidator.detect(JPEG)).isEqualTo("image/jpeg");
    assertThat(QrImageContentTypeValidator.detect(WEBP)).isEqualTo("image/webp");
  }

  @Test
  void unrecognisedOrTruncatedBytesDetectAsNull() {
    assertThat(QrImageContentTypeValidator.detect("MZ not an image".getBytes())).isNull();
    assertThat(QrImageContentTypeValidator.detect(new byte[] {(byte) 0x89})).isNull();
    // RIFF container that is NOT webp (e.g. a WAV) must not pass.
    byte[] wav = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
    assertThat(QrImageContentTypeValidator.detect(wav)).isNull();
    assertThat(QrImageContentTypeValidator.detect(null)).isNull();
  }

  @Test
  void validateReturnsTheCanonicalTypeAndToleratesAMissingHeader() {
    assertThat(QrImageContentTypeValidator.validate("IMAGE/PNG", PNG)).isEqualTo("image/png");
    assertThat(QrImageContentTypeValidator.validate(null, JPEG)).isEqualTo("image/jpeg");
  }

  @Test
  void aSpoofedDeclaredHeaderIsRejected() {
    assertThatThrownBy(() -> QrImageContentTypeValidator.validate("image/jpeg", PNG))
        .isInstanceOf(InvalidQrImageException.class);
  }

  @Test
  void nonImageBytesAreRejectedRegardlessOfHeader() {
    assertThatThrownBy(
            () -> QrImageContentTypeValidator.validate("image/png", "#!/bin/sh".getBytes()))
        .isInstanceOf(InvalidQrImageException.class);
  }
}
