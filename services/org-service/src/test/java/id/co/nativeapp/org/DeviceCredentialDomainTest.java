package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.devicecredential.domain.DeviceCredential;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit proof of the {@link DeviceCredential} aggregate's invariants — no Spring context.
 *
 * <p>The load-bearing assertion is {@link #toStringNeverLeaksThePassword()} (rule 6): an accidental
 * {@code log.info("cred={}", credential)} anywhere in the codebase must never leak the device
 * password.
 */
class DeviceCredentialDomainTest {

  private static final UUID OUTLET_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

  @Test
  void constructsWithTheGivenFields() {
    DeviceCredential credential =
        new DeviceCredential(OUTLET_ID, "kc-sub-device-1", "till." + OUTLET_ID, "S3cretPass!");

    assertThat(credential.getId()).isNotNull();
    assertThat(credential.getOrgUnitId()).isEqualTo(OUTLET_ID);
    assertThat(credential.getKeycloakUserId()).isEqualTo("kc-sub-device-1");
    assertThat(credential.getUsername()).isEqualTo("till." + OUTLET_ID);
    assertThat(credential.getPassword()).isEqualTo("S3cretPass!");
  }

  @Test
  void rotatePasswordReplacesOnlyThePassword() {
    DeviceCredential credential =
        new DeviceCredential(OUTLET_ID, "kc-sub-device-1", "till." + OUTLET_ID, "OldPass!");

    credential.rotatePassword("NewPass!");

    assertThat(credential.getPassword()).isEqualTo("NewPass!");
    assertThat(credential.getKeycloakUserId()).isEqualTo("kc-sub-device-1");
    assertThat(credential.getUsername()).isEqualTo("till." + OUTLET_ID);
  }

  @Test
  void toStringNeverLeaksThePassword() {
    DeviceCredential credential =
        new DeviceCredential(
            OUTLET_ID, "kc-sub-device-1", "till." + OUTLET_ID, "TopSecretDevicePassword!");

    String rendered = credential.toString();

    assertThat(rendered).doesNotContain("TopSecretDevicePassword!").contains("REDACTED");
  }

  @Test
  void rejectsABlankPassword() {
    assertThatThrownBy(() -> new DeviceCredential(OUTLET_ID, "kc-sub-device-1", "till.x", "   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsANullOrgUnitId() {
    assertThatThrownBy(() -> new DeviceCredential(null, "kc-sub-device-1", "till.x", "pw"))
        .isInstanceOf(NullPointerException.class);
  }
}
