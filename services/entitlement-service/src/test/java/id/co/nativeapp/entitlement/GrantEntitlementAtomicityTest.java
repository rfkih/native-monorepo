package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.entitlement.entitlement.service.EntitlementService;
import id.co.nativeapp.entitlement.entitlement.service.EntitlementWriter;
import id.co.nativeapp.entitlement.entitlement.service.PostOutboxHook;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Atomicity (rule 3 / ENGINEERING-STANDARDS §3.2) for the API grant path: the {@code
 * tenant_entitlement} change and its {@code EntitlementGranted} outbox row commit together or not
 * at all. Mirrors restaurant-service's {@code RecordSaleAtomicityTest}.
 *
 * <p>A test-only {@link PostOutboxHook} is installed that throws AFTER the outbox write but still
 * inside {@link EntitlementWriter#grant}'s transaction. The expectation: the failure rolls the
 * whole transaction back, so NEITHER the entitlement row NOR the outbox row survives. The throwing
 * hook bean is supplied by the nested {@link ThrowingHookConfig}, which — marked {@link Primary} —
 * wins over the {@code @ConditionalOnMissingBean} no-op default in {@code EventsConfig}; declaring
 * a distinct {@code @TestConfiguration} also gives this test its own cached context so the throwing
 * hook never leaks into the other {@code @SpringBootTest} classes.
 */
@SpringBootTest
class GrantEntitlementAtomicityTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "owner-a@example.co.id";
  private static final String MODULE = "restaurant";
  static final String BOOM = "forced failure after outbox write (test hook)";

  @Autowired private EntitlementService entitlementService;

  @Test
  void aFailureAfterTheOutboxWriteRollsBackBothTheEntitlementAndTheOutboxRow() throws Exception {
    // The grant blows up after writing the outbox row, inside the transaction.
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> entitlementService.grant(MODULE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BOOM);

    // Both writes rolled back together: no entitlement row, no outbox row. Counted over the admin
    // (BYPASSRLS) connection because tenant_entitlement is FORCE RLS.
    assertThat(rowCountAsAdmin("tenant_entitlement")).isZero();
    assertThat(rowCountAsAdmin("outbox")).isZero();
  }

  private long rowCountAsAdmin(String table) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * Installs a {@link PostOutboxHook} that throws. Marked {@link Primary} so it unambiguously wins
   * injection into {@link EntitlementWriter} even though the no-op default is also present.
   */
  @TestConfiguration
  static class ThrowingHookConfig {
    @Bean
    @Primary
    PostOutboxHook throwingPostOutboxHook() {
      return entitlement -> {
        throw new IllegalStateException(BOOM);
      };
    }
  }
}
