package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.service.PostOutboxHook;
import id.co.nativeapp.carwash.wash.service.WashService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Acceptance — atomicity (rule 3). A failure AFTER the outbox writes but still inside the wash
 * transaction rolls back the wash AND the outbox rows ({@code SaleRecorded} + {@code
 * MetricPublished}) together: the outbox commits atomically with the aggregate, or not at all.
 *
 * <p>It installs a {@link PostOutboxHook} that throws, then asserts that — over the admin/BYPASSRLS
 * connection — both the {@code wash} table and the {@code outbox} are empty. The carwash module is
 * granted first so the gate lets the write proceed to the point of the forced failure.
 */
@SpringBootTest
class RecordWashAtomicityTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private WashService washService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  /**
   * Installs a {@link PostOutboxHook} that throws. A DISTINCT bean name (not {@code
   * postOutboxHook}) avoids a bean-definition override clash with the
   * {@code @ConditionalOnMissingBean} default, and {@link Primary} makes it win injection into
   * {@link id.co.nativeapp.carwash.wash.service.WashWriter WashWriter}. Declaring it also makes
   * this test's context configuration distinct, so Spring caches it separately and the throwing
   * hook never leaks into the other {@code @SpringBootTest} classes.
   */
  @TestConfiguration
  static class ThrowingHookConfig {
    @Bean
    @Primary
    PostOutboxHook throwingPostOutboxHook() {
      return wash -> {
        throw new IllegalStateException("forced failure after outbox write (atomicity test)");
      };
    }
  }

  @Test
  void aFailureAfterTheOutboxWriteRollsBackTheWashAndBothOutboxRows() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, "carwash", true));

    RecordWashCommand command =
        new RecordWashCommand(
            OUTLET,
            "bay-1",
            5_000_00L,
            "IDR",
            "wax",
            1_000_00L,
            Instant.parse("2026-06-14T08:30:00Z"),
            "wash-atomic-1");

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> washService.recordWash(command)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("forced failure");

    // The wash row and EVERY outbox row rolled back together.
    assertThat(countAsAdmin("wash")).isZero();
    assertThat(countAsAdmin("outbox")).isZero();
  }

  private long countAsAdmin(String table) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
