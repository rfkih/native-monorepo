package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.expense.domain.InvalidReceiptContentTypeException;
import id.co.nativeapp.employee.expense.domain.ReceiptContentTypeValidator;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the magic-byte content-type sniffer (ADR 0030 §8): each whitelisted format is
 * detected from its real signature, a spoofed declared header is rejected, and a truncated/short
 * input degrades to "unrecognised" rather than throwing an index-out-of-bounds.
 */
class ReceiptContentTypeValidatorTest {

  private static final byte[] JPEG_BYTES = {
    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02, 0x03, 0x04
  };
  private static final byte[] PNG_BYTES = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00
  };
  private static final byte[] WEBP_BYTES = {
    'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '
  };

  @Test
  void detectsJpegBySignature() {
    assertThat(ReceiptContentTypeValidator.detect(JPEG_BYTES))
        .isEqualTo(ReceiptContentTypeValidator.CONTENT_TYPE_JPEG);
  }

  @Test
  void detectsPngBySignature() {
    assertThat(ReceiptContentTypeValidator.detect(PNG_BYTES))
        .isEqualTo(ReceiptContentTypeValidator.CONTENT_TYPE_PNG);
  }

  @Test
  void detectsWebpBySignature() {
    assertThat(ReceiptContentTypeValidator.detect(WEBP_BYTES))
        .isEqualTo(ReceiptContentTypeValidator.CONTENT_TYPE_WEBP);
  }

  @Test
  void detectReturnsNullForAnUnrecognisedFormat() {
    byte[] plainText = "not an image, just text".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(ReceiptContentTypeValidator.detect(plainText)).isNull();
  }

  @Test
  void detectReturnsNullForNullInput() {
    assertThat(ReceiptContentTypeValidator.detect(null)).isNull();
  }

  @Test
  void detectHandlesAnEmptyArrayWithoutThrowing() {
    assertThat(ReceiptContentTypeValidator.detect(new byte[0])).isNull();
  }

  @Test
  void detectHandlesATruncatedJpegHeaderWithoutThrowing() {
    // Only the first 2 of the 3 JPEG magic bytes — must degrade to null, never AIOOBE.
    byte[] truncated = {(byte) 0xFF, (byte) 0xD8};
    assertThat(ReceiptContentTypeValidator.detect(truncated)).isNull();
  }

  @Test
  void detectHandlesATruncatedWebpHeaderWithoutThrowing() {
    // RIFF present, but the file ends before the WEBP mark at offset 8.
    byte[] truncated = {'R', 'I', 'F', 'F', 0x00, 0x00};
    assertThat(ReceiptContentTypeValidator.detect(truncated)).isNull();
  }

  @Test
  void detectRejectsRiffWithoutTheWebpMark() {
    // A RIFF container that is NOT webp (e.g. a WAV file) must not be misdetected as webp.
    byte[] riffWav = {'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'A', 'V', 'E'};
    assertThat(ReceiptContentTypeValidator.detect(riffWav)).isNull();
  }

  @Test
  void validatePassesWhenDeclaredMatchesEachDetectedFormat() {
    ReceiptContentTypeValidator.validate("image/jpeg", JPEG_BYTES);
    ReceiptContentTypeValidator.validate("image/png", PNG_BYTES);
    ReceiptContentTypeValidator.validate("image/webp", WEBP_BYTES);
    // No exception == pass.
  }

  @Test
  void validateIsCaseInsensitiveOnTheDeclaredHeader() {
    ReceiptContentTypeValidator.validate("IMAGE/JPEG", JPEG_BYTES);
  }

  @Test
  void validateRejectsASpoofedDeclaredContentType() {
    // The bytes are genuinely PNG, but the client declared jpeg — must be rejected, not silently
    // trusted.
    assertThatThrownBy(() -> ReceiptContentTypeValidator.validate("image/jpeg", PNG_BYTES))
        .isInstanceOf(InvalidReceiptContentTypeException.class)
        .hasMessageContaining("image/jpeg")
        .hasMessageContaining("image/png");
  }

  @Test
  void validateRejectsATruncatedFile() {
    byte[] truncated = {(byte) 0xFF, (byte) 0xD8};
    assertThatThrownBy(() -> ReceiptContentTypeValidator.validate("image/jpeg", truncated))
        .isInstanceOf(InvalidReceiptContentTypeException.class)
        .hasMessageContaining("unrecognised");
  }

  @Test
  void validateRejectsAnUnrecognisedFormatEvenWithNoDeclaredHeader() {
    assertThatThrownBy(() -> ReceiptContentTypeValidator.validate(null, "junk".getBytes()))
        .isInstanceOf(InvalidReceiptContentTypeException.class);
  }

  @Test
  void validateToleratesAMissingDeclaredHeaderWhenTheBytesMatchAWhitelistedSignature() {
    // E3 review S2: browsers always send a part Content-Type, some API clients don't — the magic
    // bytes are the authority; a null header with genuine JPEG bytes passes.
    byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
    assertThat(ReceiptContentTypeValidator.validate(null, jpeg))
        .isEqualTo(ReceiptContentTypeValidator.CONTENT_TYPE_JPEG);
  }

  @Test
  void validateReturnsTheCanonicalConstantForACaseVariantDeclaredHeader() {
    // E3 review S1: the stored (and later served) value is the canonical detected constant — a
    // case/whitespace variant of the declared header never leaks into the DB.
    byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
    assertThat(ReceiptContentTypeValidator.validate("  IMAGE/JPEG ", jpeg))
        .isEqualTo(ReceiptContentTypeValidator.CONTENT_TYPE_JPEG);
  }
}
