package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.payment.settings.domain.InvalidQrImageException;
import id.co.nativeapp.payment.settings.domain.PaymentSettings;
import id.co.nativeapp.payment.settings.domain.PaymentSettingsNotFoundException;
import id.co.nativeapp.payment.settings.domain.SettingsForbiddenException;
import id.co.nativeapp.payment.settings.domain.SettingsValidationException;
import id.co.nativeapp.payment.settings.dto.EffectiveSettingsResponse;
import id.co.nativeapp.payment.settings.dto.PaymentSettingsResponse;
import id.co.nativeapp.payment.settings.dto.QrImageContentResponse;
import id.co.nativeapp.payment.settings.dto.QrImageMetaResponse;
import id.co.nativeapp.payment.settings.dto.UpsertSettingsRequest;
import id.co.nativeapp.payment.settings.service.SettingsService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The payment-settings surface end-to-end at the service layer (ADR 0045): upsert + effective
 * resolution (outlet override → company default → implicit MANUAL, per-facet image fallback), the
 * write-only server key (encrypted at rest, only last4 ever readable), the owner role guard, and
 * the untrusted-upload guards. Role simulation mirrors loyalty's {@code EarnRuleAcceptanceTest}
 * {@code setRoles} idiom (a {@link MockHttpServletRequest} bound to {@link RequestContextHolder}).
 */
@SpringBootTest
class PaymentSettingsAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT = "77777777-7777-7777-7777-777777777777";
  private static final String ACTOR = "owner@example.co.id";
  private static final UUID OUTLET = UUID.fromString("b555d5b8-e17b-4990-a7dd-2c4be199d7a6");

  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9};

  @Autowired private SettingsService service;

  @BeforeEach
  void resetTableAndBindRequest() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE payment_settings");
    }
    setRoles("owner");
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private void setRoles(String roles) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (roles != null) {
      request.addHeader("X-Roles", roles);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private static UpsertSettingsRequest modeOnly(String mode) {
    return new UpsertSettingsRequest(mode, null, null, null, null);
  }

  @Test
  void staticModeWithImageResolvesAndServesTheExactBytes() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          service.upsertCompanyDefault(modeOnly("STATIC"));
          // Mode before image: legal, but not yet available — the console falls back to MANUAL.
          EffectiveSettingsResponse before = service.effective(OUTLET);
          assertThat(before.mode()).isEqualTo("STATIC");
          assertThat(before.staticQrAvailable()).isFalse();

          QrImageMetaResponse meta = service.uploadStaticQr(null, "image/png", PNG);
          assertThat(meta.contentType()).isEqualTo("image/png");
          assertThat(meta.byteSize()).isEqualTo(PNG.length);

          EffectiveSettingsResponse after = service.effective(OUTLET);
          assertThat(after.staticQrAvailable()).isTrue();

          QrImageContentResponse image = service.effectiveImage(OUTLET);
          assertThat(image.contentType()).isEqualTo("image/png");
          assertThat(image.data()).isEqualTo(PNG);
          assertThat(image.sha256()).isEqualTo(meta.sha256().strip());
          return null;
        });
  }

  @Test
  void outletOverrideWinsForModeAndFallsBackToTheCompanyImage() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          service.upsertCompanyDefault(modeOnly("MANUAL"));
          service.uploadStaticQr(null, "image/png", PNG);
          service.upsertOutletOverride(OUTLET, modeOnly("STATIC"));

          EffectiveSettingsResponse effective = service.effective(OUTLET);
          assertThat(effective.mode()).isEqualTo("STATIC");
          // The override row has no image of its own — the company image must fall back.
          assertThat(effective.staticQrAvailable()).isTrue();
          assertThat(service.effectiveImage(OUTLET).data()).isEqualTo(PNG);

          // A different outlet (no override) resolves the company default mode.
          assertThat(service.effective(UUID.randomUUID()).mode()).isEqualTo("MANUAL");
          return null;
        });
  }

  @Test
  void serverKeyIsWriteOnlyEncryptedAtRestAndRetainedOnResave() throws Exception {
    String serverKey = "SB-Mid-server-abcdefghij1234";
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          PaymentSettingsResponse saved =
              service.upsertCompanyDefault(
                  new UpsertSettingsRequest(
                      "GATEWAY", "MIDTRANS", "SANDBOX", serverKey, "SB-Mid-client-xyz"));
          assertThat(saved.companyDefault().gateway().connected()).isTrue();
          assertThat(saved.companyDefault().gateway().serverKeyLast4()).isEqualTo("1234");
          assertThat(saved.companyDefault().gateway().environment()).isEqualTo("SANDBOX");

          // Re-save WITHOUT the key (the console re-sends the form key-less): key retained.
          PaymentSettingsResponse resaved =
              service.upsertCompanyDefault(
                  new UpsertSettingsRequest("GATEWAY", "MIDTRANS", "SANDBOX", null, null));
          assertThat(resaved.companyDefault().gateway().connected()).isTrue();
          assertThat(resaved.companyDefault().gateway().serverKeyLast4()).isEqualTo("1234");

          EffectiveSettingsResponse effective = service.effective(null);
          assertThat(effective.gateway().connected()).isTrue();
          return null;
        });

    // Rule 6 at-rest proof over the ADMIN connection (BYPASSRLS): the stored column must be
    // ciphertext — the plaintext key must appear nowhere in the stored bytes.
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT server_key_encrypted, server_key_last4 FROM payment_settings"
                    + " WHERE outlet_id IS NULL")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        byte[] stored = rs.getBytes(1);
        assertThat(stored).isNotNull();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
            .doesNotContain(serverKey);
        assertThat(indexOf(stored, serverKey.getBytes())).isEqualTo(-1);
        assertThat(rs.getString(2)).isEqualTo("1234");
      }
    }
  }

  @Test
  void aCallerWhosePresentRolesLackOwnerIsRejectedFromWritesButNotEffective() throws Exception {
    setRoles("cashier");
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          assertThatThrownBy(() -> service.upsertCompanyDefault(modeOnly("STATIC")))
              .isInstanceOf(SettingsForbiddenException.class);
          assertThatThrownBy(() -> service.uploadStaticQr(null, "image/png", PNG))
              .isInstanceOf(SettingsForbiddenException.class);
          assertThatThrownBy(() -> service.list()).isInstanceOf(SettingsForbiddenException.class);
          // The till's reads stay open to POS roles.
          assertThat(service.effective(OUTLET).mode()).isEqualTo("MANUAL");
          return null;
        });
  }

  @Test
  void invalidInputsAreRejectedWithTheRightFaults() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          assertThatThrownBy(() -> service.upsertCompanyDefault(modeOnly("BOGUS")))
              .isInstanceOf(SettingsValidationException.class);
          // Credentials on an outlet override: company-level only (ADR 0045).
          assertThatThrownBy(
                  () ->
                      service.upsertOutletOverride(
                          OUTLET,
                          new UpsertSettingsRequest("GATEWAY", "MIDTRANS", "SANDBOX", "key", null)))
              .isInstanceOf(SettingsValidationException.class);
          // Spoofed header: PNG bytes declared as jpeg.
          assertThatThrownBy(() -> service.uploadStaticQr(null, "image/jpeg", PNG))
              .isInstanceOf(InvalidQrImageException.class);
          // Garbage bytes.
          assertThatThrownBy(() -> service.uploadStaticQr(null, "image/png", "MZ".getBytes()))
              .isInstanceOf(InvalidQrImageException.class);
          // Oversize (JPEG magic so the size check, which runs first, is provably the rejector).
          byte[] oversized = Arrays.copyOf(JPEG, PaymentSettings.MAX_QR_IMAGE_BYTES + 1);
          assertThatThrownBy(() -> service.uploadStaticQr(null, "image/jpeg", oversized))
              .isInstanceOf(MaxUploadSizeExceededException.class);
          // Deleting what does not exist.
          assertThatThrownBy(() -> service.deleteOutletOverride(UUID.randomUUID()))
              .isInstanceOf(PaymentSettingsNotFoundException.class);
          assertThatThrownBy(() -> service.removeStaticQr(null))
              .isInstanceOf(PaymentSettingsNotFoundException.class);
          return null;
        });
  }

  @Test
  void removingTheImageAndTheOverrideRestoresTheBaseline() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          service.upsertCompanyDefault(modeOnly("STATIC"));
          service.uploadStaticQr(null, "image/png", PNG);
          service.upsertOutletOverride(OUTLET, modeOnly("MANUAL"));
          assertThat(service.effective(OUTLET).mode()).isEqualTo("MANUAL");

          service.deleteOutletOverride(OUTLET);
          assertThat(service.effective(OUTLET).mode()).isEqualTo("STATIC");

          service.removeStaticQr(null);
          assertThat(service.effective(OUTLET).staticQrAvailable()).isFalse();
          assertThatThrownBy(() -> service.effectiveImage(OUTLET))
              .isInstanceOf(PaymentSettingsNotFoundException.class);
          return null;
        });
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
