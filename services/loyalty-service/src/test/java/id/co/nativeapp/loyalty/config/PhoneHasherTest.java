package id.co.nativeapp.loyalty.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PhoneHasher}'s tenant-mixed HMAC message (security review W-1). A plain
 * unit test — {@link PhoneHasher#fromBase64Key} needs no Spring context, just a valid 32-byte
 * base64 key (the same dev/test default {@code application.yml}/{@code build.gradle.kts} wire in
 * via {@code NATIVE_PII_HMAC_KEY}).
 */
class PhoneHasherTest {

  // The committed dev/test-only placeholder key (see application.yml / build.gradle.kts) — NOT a
  // real secret.
  private static final String TEST_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

  private final PhoneHasher hasher = PhoneHasher.fromBase64Key(TEST_KEY);

  @Test
  void theSamePhoneUnderTwoDifferentTenantsHashesToDifferentDigests() {
    String phone = "081234567890";
    String tenantA = "11111111-1111-1111-1111-111111111111";
    String tenantB = "22222222-2222-2222-2222-222222222222";

    String hashA = hasher.hash(tenantA, phone);
    String hashB = hasher.hash(tenantB, phone);

    // Pre-W-1 the hasher ignored the tenant entirely, so this assertion would have failed
    // (hashA == hashB for the same phone regardless of tenant) — the exact cross-tenant
    // same-person-linkage leak the fix closes.
    assertThat(hashA)
        .as("tenant-mixed HMAC: the same phone must hash differently per company_id")
        .isNotEqualTo(hashB);
  }

  @Test
  void hashingIsDeterministicForTheSameTenantAndPhone() {
    String phone = "081234567890";
    String tenant = "33333333-3333-3333-3333-333333333333";

    assertThat(hasher.hash(tenant, phone))
        .as("the exact-match UNIQUE(company_id, phone_hash) lookup requires a stable digest")
        .isEqualTo(hasher.hash(tenant, phone));
  }

  @Test
  void differentPhonesUnderTheSameTenantHashDifferently() {
    String tenant = "44444444-4444-4444-4444-444444444444";

    assertThat(hasher.hash(tenant, "081200000001"))
        .isNotEqualTo(hasher.hash(tenant, "081200000002"));
  }
}
