package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The headline feature under CONCURRENCY: two threads record a sale at the same time
 * with the SAME {@code idempotency_key} in the SAME tenant scope.
 *
 * <p>Proves the concurrency-safe idempotency contract of {@link SaleService}:
 * <ul>
 *   <li>exactly ONE {@code sale} row exists (counted over the admin/BYPASSRLS
 *       connection, since {@code sale} is {@code FORCE} RLS);</li>
 *   <li>exactly ONE {@code SaleRecorded} outbox row exists (the conflict path writes
 *       no second event);</li>
 *   <li>BOTH callers receive a successful idempotent result — no exception, no 500 —
 *       with exactly one {@code created=true} and one {@code created=false}, both
 *       resolving to the same sale id.</li>
 * </ul>
 *
 * <p>The loser of the unique-constraint race trips a
 * {@code DataIntegrityViolationException}, which aborts its create transaction; the
 * service recovers it with a separate-transaction re-read and returns the winner's
 * sale with {@code created=false}.
 */
@SpringBootTest
class RecordSaleConcurrencyTest extends PostgresRlsTestBase {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR_A = "cashier-a@example.co.id";

    @Autowired
    private SaleService saleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentSameKeyYieldsOneSaleOneEventAndTwoSuccessfulResults() throws Exception {
        UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String idempotencyKey = "race-key-123";
        Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
        RecordSaleCommand command = new RecordSaleCommand(
                businessId, 1_500_000L, "IDR", occurredAt, idempotencyKey);

        // A barrier so both threads enter recordSale as close to simultaneously as
        // possible, maximizing the chance both miss the idempotency short-circuit and
        // race the INSERT (the loser must then be recovered, not 500).
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<RecordSaleResult> attempt = () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> {
            barrier.await();
            return saleService.recordSale(command);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecordSaleResult> f1 = pool.submit(attempt);
            Future<RecordSaleResult> f2 = pool.submit(attempt);

            // Neither call throws (no 500) — both return a successful idempotent result.
            RecordSaleResult r1 = f1.get();
            RecordSaleResult r2 = f2.get();

            // Exactly one created=true and one created=false, both the same sale id.
            assertThat(r1.created() ^ r2.created())
                    .as("exactly one caller created the sale, the other was idempotent")
                    .isTrue();
            assertThat(r1.sale().getId())
                    .as("both callers resolve to the same single sale")
                    .isEqualTo(r2.sale().getId());
        } finally {
            pool.shutdownNow();
        }

        // Exactly ONE sale row, counted over the admin (BYPASSRLS) connection.
        assertThat(saleRowCountAsAdmin()).isEqualTo(1L);

        // Exactly ONE SaleRecorded outbox row — the conflict path emitted nothing.
        Long outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'", Long.class);
        assertThat(outboxCount).isEqualTo(1L);
    }

    private long saleRowCountAsAdmin() throws Exception {
        try (Connection admin = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = admin.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM sale")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
