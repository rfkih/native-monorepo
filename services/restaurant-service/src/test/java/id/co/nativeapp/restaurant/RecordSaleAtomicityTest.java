package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Atomicity (rule 3) for THIS service: the sale and its {@code SaleRecorded} outbox row
 * commit together or not at all.
 *
 * <p>A test-only {@link PostOutboxHook} is installed that throws AFTER the outbox write
 * but still inside {@link SaleWriter#create}'s transaction. The expectation: the failure
 * rolls the whole transaction back, so NEITHER the sale row NOR the outbox row survives.
 * The throwing hook bean is supplied by the nested {@link ThrowingHookConfig}, which (by
 * being a concrete bean) wins over the {@code @ConditionalOnMissingBean} no-op default.
 */
@SpringBootTest
class RecordSaleAtomicityTest extends PostgresRlsTestBase {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR_A = "cashier-a@example.co.id";
    static final String BOOM = "forced failure after outbox write (test hook)";

    @Autowired
    private SaleService saleService;

    @Test
    void aFailureAfterTheOutboxWriteRollsBackBothTheSaleAndTheOutboxRow() throws Exception {
        UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        RecordSaleCommand command = new RecordSaleCommand(
                businessId, 1_500_000L, "IDR", Instant.parse("2026-06-14T08:30:00Z"), "atomic-key");

        // The create blows up after writing the outbox row, inside the transaction.
        assertThatThrownBy(() ->
                TenantContext.callAs(TENANT_A, ACTOR_A, () -> saleService.recordSale(command)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BOOM);

        // Both writes rolled back together: no sale, no outbox row. Counted over the
        // admin (BYPASSRLS) connection because `sale` is FORCE RLS.
        assertThat(rowCountAsAdmin("sale")).isZero();
        assertThat(rowCountAsAdmin("outbox")).isZero();
    }

    private long rowCountAsAdmin(String table) throws Exception {
        try (Connection admin = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = admin.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Installs a {@link PostOutboxHook} that throws. Marked {@link Primary} so it
     * unambiguously wins injection into {@link SaleWriter} even if the no-op default
     * is also present; declaring it also makes this test's context configuration
     * distinct, so Spring caches it separately and the throwing hook never leaks into
     * the other {@code @SpringBootTest} classes.
     */
    @TestConfiguration
    static class ThrowingHookConfig {
        @Bean
        @Primary
        PostOutboxHook throwingPostOutboxHook() {
            return sale -> {
                throw new IllegalStateException(BOOM);
            };
        }
    }
}
