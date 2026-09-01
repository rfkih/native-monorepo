package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.payment.charge.service.QrisGatewayPort;
import id.co.nativeapp.payment.settings.domain.InvalidQrImageException;
import id.co.nativeapp.payment.settings.domain.PaymentSettings;
import id.co.nativeapp.payment.settings.domain.PaymentSettingsNotFoundException;
import id.co.nativeapp.payment.settings.domain.ProviderEnvironment;
import id.co.nativeapp.payment.settings.domain.SettingsForbiddenException;
import id.co.nativeapp.payment.settings.domain.SettingsValidationException;
import id.co.nativeapp.payment.settings.dto.EffectiveSettingsResponse;
import id.co.nativeapp.payment.settings.dto.GatewayVerifyRequest;
import id.co.nativeapp.payment.settings.dto.GatewayVerifyResponse;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The payment-settings surface end-to-end at the service layer (ADR 0045; the DIVISION rung was
 * removed with the division level itself in ADR 0070): upsert + effective resolution (outlet
 * override → company default → implicit MANUAL, per-facet image fallback), the write-only server
 * key (encrypted at rest, only last4 ever readable), the owner role guard, and the untrusted-upload
 * guards. Role simulation mirrors loyalty's {@code EarnRuleAcceptanceTest} {@code setRoles} idiom
 * (a {@link MockHttpServletRequest} bound to {@link RequestContextHolder}).
 */
@SpringBootTest
class PaymentSettingsAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT = "77777777-7777-7777-7777-777777777777";
  private static final String ACTOR = "owner@example.co.id";
  private static final UUID OUTLET = UUID.fromString("b555d5b8-e17b-4990-a7dd-2c4be199d7a6");

  /** A second outlet id — the per-unit rules must hold for every unit row, not just the first. */
  private static final UUID OTHER_UNIT = UUID.fromString("c666d5b8-e17b-4990-a7dd-2c4be199d7a6");

  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9};

  @Autowired private SettingsService service;
  // The verify probe's gateway port is replaced with a recording double (@Primary) so we can assert
  // it is called with the REQUESTED environment + that environment's own key — never crossed.
  @Autowired private QrisGatewayPort gatewayPort;

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
    return new UpsertSettingsRequest(mode, null, null, null, null, null, null);
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
          service.upsertUnitOverride(OUTLET, modeOnly("STATIC"));

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
  void anOutletOverrideBeatsTheCompanyDefault() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          service.upsertCompanyDefault(modeOnly("STATIC"));
          service.uploadStaticQr(null, "image/png", PNG);
          // The outlet has its OWN row — it wins over the company default, mode AND image.
          service.upsertUnitOverride(OUTLET, modeOnly("GATEWAY"));
          byte[] outletPng = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9};
          service.uploadStaticQr(OUTLET, "image/png", outletPng);

          EffectiveSettingsResponse effective = service.effective(OUTLET);
          assertThat(effective.mode()).isEqualTo("GATEWAY");
          assertThat(service.effectiveImage(OUTLET).data()).isEqualTo(outletPng);
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
                      "GATEWAY",
                      "MIDTRANS",
                      "SANDBOX",
                      serverKey,
                      "SB-Mid-client-xyz",
                      null,
                      null));
          assertThat(saved.companyDefault().gateway().sandbox().connected()).isTrue();
          assertThat(saved.companyDefault().gateway().sandbox().serverKeyLast4()).isEqualTo("1234");
          assertThat(saved.companyDefault().gateway().activeEnvironment()).isEqualTo("SANDBOX");
          // The PRODUCTION slot is untouched — its own key lives independently (V6).
          assertThat(saved.companyDefault().gateway().production().connected()).isFalse();

          // Re-save WITHOUT the key (the console re-sends the form key-less): the SANDBOX slot's
          // key is retained, and re-activating SANDBOX is allowed because that slot still has a
          // key.
          PaymentSettingsResponse resaved =
              service.upsertCompanyDefault(
                  new UpsertSettingsRequest(
                      "GATEWAY", "MIDTRANS", "SANDBOX", null, null, null, null));
          assertThat(resaved.companyDefault().gateway().sandbox().connected()).isTrue();
          assertThat(resaved.companyDefault().gateway().sandbox().serverKeyLast4())
              .isEqualTo("1234");

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
                "SELECT sandbox_server_key_encrypted, sandbox_server_key_last4 FROM payment_settings"
                    + " WHERE org_unit_id IS NULL")) {
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
  void sandboxAndProductionKeysAreStoredIndependentlyAndSwitchWithoutReentry() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          // Store a SANDBOX key and activate sandbox.
          service.upsertCompanyDefault(
              new UpsertSettingsRequest(
                  "GATEWAY", "MIDTRANS", "SANDBOX", "SB-Mid-server-sand9999", null, null, null));

          // Add a PRODUCTION key in its OWN slot (sandbox untouched) and activate production.
          PaymentSettingsResponse both =
              service.upsertCompanyDefault(
                  new UpsertSettingsRequest(
                      "GATEWAY",
                      "MIDTRANS",
                      "PRODUCTION",
                      null,
                      null,
                      "Mid-server-prod8888",
                      null));
          assertThat(both.companyDefault().gateway().activeEnvironment()).isEqualTo("PRODUCTION");
          assertThat(both.companyDefault().gateway().sandbox().connected()).isTrue();
          assertThat(both.companyDefault().gateway().sandbox().serverKeyLast4()).isEqualTo("9999");
          assertThat(both.companyDefault().gateway().production().connected()).isTrue();
          assertThat(both.companyDefault().gateway().production().serverKeyLast4())
              .isEqualTo("8888");

          // Switch the ACTIVE environment back to SANDBOX WITHOUT re-entering any key — the
          // structural fix for the mismatch trap: each key stays in its own slot.
          PaymentSettingsResponse switched =
              service.upsertCompanyDefault(
                  new UpsertSettingsRequest(
                      "GATEWAY", "MIDTRANS", "SANDBOX", null, null, null, null));
          assertThat(switched.companyDefault().gateway().activeEnvironment()).isEqualTo("SANDBOX");
          assertThat(switched.companyDefault().gateway().sandbox().serverKeyLast4())
              .isEqualTo("9999");
          assertThat(switched.companyDefault().gateway().production().serverKeyLast4())
              .isEqualTo("8888");
          return null;
        });
  }

  @Test
  void activatingAnEnvironmentWhoseSlotHasNoKeyIsRejected() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          // Only a SANDBOX key is stored.
          service.upsertCompanyDefault(
              new UpsertSettingsRequest(
                  "GATEWAY", "MIDTRANS", "SANDBOX", "SB-Mid-server-aaaa1111", null, null, null));
          // Activating PRODUCTION (empty slot) is refused — no env can be activated against
          // another environment's (or no) key.
          assertThatThrownBy(
                  () ->
                      service.upsertCompanyDefault(
                          new UpsertSettingsRequest(
                              "GATEWAY", "MIDTRANS", "PRODUCTION", null, null, null, null)))
              .isInstanceOf(SettingsValidationException.class);
          // The failed activation committed nothing — sandbox is still the active environment.
          assertThat(service.list().companyDefault().gateway().activeEnvironment())
              .isEqualTo("SANDBOX");
          return null;
        });
  }

  @Test
  void verifyingWithNoStoredKeyAndNoSuppliedKeyIsRejected() throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          // No credentials configured at all → verify has nothing to probe (never touches the PSP).
          assertThatThrownBy(
                  () -> service.verifyGateway(new GatewayVerifyRequest("PRODUCTION", null)))
              .isInstanceOf(SettingsValidationException.class);
          return null;
        });
  }

  @Test
  void verifyProbesTheRequestedEnvironmentsOwnStoredKeyNeverCrossed() throws Exception {
    RecordingGatewayPort recording = (RecordingGatewayPort) gatewayPort;
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          // Store DISTINCT keys in each slot (active SANDBOX).
          service.upsertCompanyDefault(
              new UpsertSettingsRequest(
                  "GATEWAY",
                  "MIDTRANS",
                  "SANDBOX",
                  "SB-Mid-server-sbx0001",
                  null,
                  "Mid-server-prod0002",
                  null));

          // Verify SANDBOX (no supplied key) → probes the SANDBOX slot's key at the SANDBOX env.
          recording.result = QrisGatewayPort.GatewayVerification.VALID;
          GatewayVerifyResponse sandbox =
              service.verifyGateway(new GatewayVerifyRequest("SANDBOX", null));
          assertThat(sandbox.result()).isEqualTo("VALID");
          assertThat(recording.lastVerified.environment()).isEqualTo(ProviderEnvironment.SANDBOX);
          assertThat(recording.lastVerified.serverKey()).isEqualTo("SB-Mid-server-sbx0001");

          // Verify PRODUCTION → probes the PRODUCTION slot's key at the PRODUCTION env — never
          // crossed (the exact failure mode this feature exists to prevent, on the read side).
          recording.result = QrisGatewayPort.GatewayVerification.INVALID;
          GatewayVerifyResponse production =
              service.verifyGateway(new GatewayVerifyRequest("PRODUCTION", null));
          assertThat(production.result()).isEqualTo("INVALID");
          assertThat(recording.lastVerified.environment())
              .isEqualTo(ProviderEnvironment.PRODUCTION);
          assertThat(recording.lastVerified.serverKey()).isEqualTo("Mid-server-prod0002");

          // A SUPPLIED key overrides the stored one, still at the requested environment.
          recording.result = QrisGatewayPort.GatewayVerification.VALID;
          service.verifyGateway(new GatewayVerifyRequest("SANDBOX", "SB-Mid-server-typed"));
          assertThat(recording.lastVerified.environment()).isEqualTo(ProviderEnvironment.SANDBOX);
          assertThat(recording.lastVerified.serverKey()).isEqualTo("SB-Mid-server-typed");

          // After activating PRODUCTION, the till's effective read reports the ACTIVE (production)
          // slot as connected — the CASE-by-active-env gateway_connected follows the switch.
          service.upsertCompanyDefault(
              new UpsertSettingsRequest(
                  "GATEWAY", "MIDTRANS", "PRODUCTION", null, null, null, null));
          EffectiveSettingsResponse effective = service.effective(null);
          assertThat(effective.gateway().environment()).isEqualTo("PRODUCTION");
          assertThat(effective.gateway().connected()).isTrue();
          return null;
        });
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
                      service.upsertUnitOverride(
                          OUTLET,
                          new UpsertSettingsRequest(
                              "GATEWAY", "MIDTRANS", "SANDBOX", "key", null, null, null)))
              .isInstanceOf(SettingsValidationException.class);
          // ...and on ANY other unit row identically — gateway credentials are company-level
          // regardless of which unit the row is for.
          assertThatThrownBy(
                  () ->
                      service.upsertUnitOverride(
                          OTHER_UNIT,
                          new UpsertSettingsRequest(
                              "GATEWAY", "MIDTRANS", "SANDBOX", "key", null, null, null)))
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
          assertThatThrownBy(() -> service.deleteUnitOverride(UUID.randomUUID()))
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
          service.upsertUnitOverride(OUTLET, modeOnly("MANUAL"));
          assertThat(service.effective(OUTLET).mode()).isEqualTo("MANUAL");

          service.deleteUnitOverride(OUTLET);
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

  @TestConfiguration
  static class RecordingGatewayConfig {
    @Bean
    @Primary
    RecordingGatewayPort recordingGatewayPort() {
      return new RecordingGatewayPort();
    }
  }

  /**
   * A {@link QrisGatewayPort} double that RECORDS the credentials passed to {@link #verify} (the
   * only method the settings tests exercise) so a test can assert the environment + key are never
   * crossed. The charge-side methods are unused here and fail loudly if ever called.
   */
  static final class RecordingGatewayPort implements QrisGatewayPort {
    volatile GatewayCredentials lastVerified;
    volatile GatewayVerification result = GatewayVerification.VALID;

    @Override
    public QrCreated createQr(GatewayCredentials c, UUID companyId, String orderId, long amount) {
      throw new UnsupportedOperationException("not used in settings tests");
    }

    @Override
    public RemoteCharge status(GatewayCredentials c, String orderId) {
      throw new UnsupportedOperationException("not used in settings tests");
    }

    @Override
    public CancelOutcome cancel(GatewayCredentials c, String orderId) {
      throw new UnsupportedOperationException("not used in settings tests");
    }

    @Override
    public GatewayVerification verify(GatewayCredentials credentials) {
      this.lastVerified = credentials;
      return result;
    }
  }
}
