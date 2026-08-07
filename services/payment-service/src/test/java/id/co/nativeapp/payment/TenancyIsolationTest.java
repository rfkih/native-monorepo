package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.payment.settings.domain.PaymentSettingsNotFoundException;
import id.co.nativeapp.payment.settings.dto.UpsertSettingsRequest;
import id.co.nativeapp.payment.settings.service.SettingsService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Rule 5 proof for {@code payment_settings}, running as the unprivileged {@code app_user} (no
 * BYPASSRLS): tenant B must see NOTHING of tenant A's settings — not the mode, not the image, not
 * even existence. The FORCE policy makes this hold even for the table-owning role.
 */
@SpringBootTest
class TenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String ACTOR = "isolation@example.co.id";

  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

  @Autowired private SettingsService service;

  @BeforeEach
  void resetTableAndBindRequest() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("TRUNCATE TABLE payment_settings");
    }
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void tenantBSeesNothingOfTenantA() throws Exception {
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          service.upsertCompanyDefault(
              new UpsertSettingsRequest("GATEWAY", "MIDTRANS", "SANDBOX", "SB-Mid-a-1234", null));
          service.uploadStaticQr(null, "image/png", PNG);
          return null;
        });

    TenantContext.callAs(
        TENANT_B,
        ACTOR,
        () -> {
          assertThat(service.list().companyDefault()).isNull();
          assertThat(service.list().outletOverrides()).isEmpty();
          // Effective resolution falls all the way through to the implicit default.
          assertThat(service.effective(null, null).mode()).isEqualTo("MANUAL");
          assertThat(service.effective(null, null).staticQrAvailable()).isFalse();
          assertThat(service.effective(null, null).gateway()).isNull();
          assertThatThrownBy(() -> service.effectiveImage(null, null))
              .isInstanceOf(PaymentSettingsNotFoundException.class);
          return null;
        });

    // Tenant A still sees its own row (the isolation is directional, not data loss).
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(service.list().companyDefault()).isNotNull();
          assertThat(service.effective(null, null).mode()).isEqualTo("GATEWAY");
          return null;
        });
  }
}
