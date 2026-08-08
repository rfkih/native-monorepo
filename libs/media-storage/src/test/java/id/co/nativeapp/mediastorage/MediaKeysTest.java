package id.co.nativeapp.mediastorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * Key-shape and whitelist tests: no request-derived value may smuggle path structure into a key.
 */
class MediaKeysTest {

  private static final String SHA = "a".repeat(64);
  private static final String COMPANY = "0198c1c2-4b7d-7c3e-9d21-5c1b2a3d4e5f";

  @Test
  void buildsTheCanonicalShape() {
    assertThat(MediaKeys.imageKey("restaurant", COMPANY, "menu", SHA, "image/jpeg"))
        .isEqualTo("restaurant/" + COMPANY + "/menu/" + SHA + ".jpg");
    assertThat(MediaKeys.imageKey("payment", COMPANY, "qr", SHA, "image/png"))
        .isEqualTo("payment/" + COMPANY + "/qr/" + SHA + ".png");
    assertThat(MediaKeys.extensionFor("image/webp")).isEqualTo("webp");
  }

  @Test
  void rejectsPathShapedSegments() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaKeys.imageKey("restaurant", "../other", "menu", SHA, "image/jpeg"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> MediaKeys.imageKey("restaurant", COMPANY, "menu/../x", SHA, "image/jpeg"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaKeys.imageKey("Restaurant", COMPANY, "menu", SHA, "image/jpeg"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaKeys.imageKey("restaurant", COMPANY, "menu", "short", "image/jpeg"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaKeys.imageKey("restaurant", COMPANY, "menu", SHA, "image/gif"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaKeys.imageKey("restaurant", null, "menu", SHA, "image/jpeg"));
  }

  @Test
  void sha256HexFeedsTheKeyDirectly() {
    String hex = Sha256.hex(new byte[] {1, 2, 3});
    assertThat(hex).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(MediaKeys.imageKey("employee", COMPANY, "receipt", hex, "image/png"))
        .endsWith("/" + hex + ".png");
  }
}
