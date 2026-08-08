package id.co.nativeapp.mediastorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Magic-byte table tests for the fleet's unified image validator (ADR 0048). */
class ImageContentTypeValidatorTest {

  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1};
  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] WEBP = {
    'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '
  };

  @Test
  void detectsTheThreeWhitelistedSignatures() {
    assertThat(ImageContentTypeValidator.detect(JPEG)).isEqualTo("image/jpeg");
    assertThat(ImageContentTypeValidator.detect(PNG)).isEqualTo("image/png");
    assertThat(ImageContentTypeValidator.detect(WEBP)).isEqualTo("image/webp");
  }

  @Test
  void rejectsUnrecognisedAndShortInputWithoutThrowingOutOfBounds() {
    assertThat(ImageContentTypeValidator.detect(new byte[] {0x00, 0x01})).isNull();
    assertThat(ImageContentTypeValidator.detect(new byte[] {'R', 'I', 'F', 'F', 0x01})).isNull();
    assertThat(ImageContentTypeValidator.detect(new byte[0])).isNull();
    assertThat(ImageContentTypeValidator.detect(null)).isNull();
  }

  @Test
  void validateReturnsCanonicalTypeAndToleratesMissingDeclaredHeader() {
    assertThat(ImageContentTypeValidator.validate("image/jpeg", JPEG)).isEqualTo("image/jpeg");
    assertThat(ImageContentTypeValidator.validate("IMAGE/JPEG ", JPEG)).isEqualTo("image/jpeg");
    assertThat(ImageContentTypeValidator.validate(null, PNG)).isEqualTo("image/png");
  }

  @Test
  void validateRejectsSpoofedHeaderAndUnknownBytes() {
    assertThatThrownBy(() -> ImageContentTypeValidator.validate("image/png", JPEG))
        .isInstanceOf(UnsupportedImageTypeException.class);
    assertThatThrownBy(() -> ImageContentTypeValidator.validate("image/jpeg", new byte[] {1, 2, 3}))
        .isInstanceOf(UnsupportedImageTypeException.class);
    assertThatThrownBy(() -> ImageContentTypeValidator.validate(null, new byte[] {1, 2, 3}))
        .isInstanceOf(UnsupportedImageTypeException.class);
  }
}
