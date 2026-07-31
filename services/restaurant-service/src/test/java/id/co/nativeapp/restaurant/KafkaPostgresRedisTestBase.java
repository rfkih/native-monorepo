package id.co.nativeapp.restaurant;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for the Phase 6 (ADR 0029) self-order entitlement-gate tests: a real PostgreSQL 16 (as the
 * unprivileged {@code app_user}, so RLS engages), a real Kafka broker (publish/await the consumed
 * {@code EntitlementGranted}/{@code EntitlementRevoked} Avro bytes), and a real Redis 7 (the
 * entitlement-check cache the self-order-create gate reads on every call).
 *
 * <p><strong>Deliberately does NOT extend {@link PostgresRlsTestBase}.</strong> That class's
 * {@code @DynamicPropertySource datasourceProperties} ALSO pins {@code
 * spring.kafka.bootstrap-servers} to a closed port (so the rest of this fleet's tests, which need
 * no broker, never talk to a foreign local Kafka). Spring resolves {@code @DynamicPropertySource}
 * methods across a class hierarchy leaf-class-first but a LATER-registered value for the SAME key
 * WINS (last write to the backing map) — so a superclass method registering AFTER a subclass one
 * would silently overwrite this class's real Testcontainers Kafka endpoint back to the closed port.
 * Rather than depend on that (undocumented, easy-to-regress) ordering, this base stands up its own
 * independent Postgres container — the ~30 extra lines are cheap insurance against a fleet-wide
 * shared-test-infra footgun.
 *
 * <p>Mirrors barbershop-service's/carwash-service's {@code KafkaPostgresRedisTestBase} in spirit
 * (same three containers, same Redis-flush-between-tests discipline); Kafka is the modern KRaft
 * {@code apache/kafka} image (no ZooKeeper), Redis a plain {@code redis:7-alpine} {@link
 * GenericContainer}. All three are singletons started once and reaped by Ryuk at JVM exit.
 */
public abstract class KafkaPostgresRedisTestBase {

  static final String APP_USER = "app_user";
  static final String APP_PASSWORD = "app_secret";

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  public static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

  @SuppressWarnings("resource") // reaped by the Testcontainers/Ryuk shutdown hook at JVM exit
  public static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    POSTGRES.start();
    KAFKA.start();
    REDIS.start();
    provisionAppRole();
  }

  /**
   * Start every test from empty tables. Truncating over the admin (BYPASSRLS) connection clears
   * rows of every tenant regardless of any session GUC. The tables may not yet exist on the very
   * first invocation before Flyway has run, so the truncate is best-effort.
   */
  @BeforeEach
  void resetTables() {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE applied_promotion, coupon, promo_rule,"
              + " bill_line_modifier, bill_line, bill,"
              + " payment, order_line_modifier, order_line, restaurant_order,"
              + " restaurant_table, menu_item_modifier_option, menu_item_modifier_group, menu_item,"
              + " menu_category, sale, gift_card_sale, gift_card_ref, member_balance_ref, outbox,"
              + " user_outlet_assignment_ref, processed_event, error_log,"
              + " self_order_access, entitlement_projection CASCADE");
    } catch (SQLException ignored) {
      // Tables not created yet (pre-Flyway) — nothing to reset.
    }
  }

  /**
   * Clear the Redis entitlement-check cache between tests so a cached entitled? answer from a prior
   * test (the cache survives the DB {@code TRUNCATE} above) cannot leak into the next — the gate
   * must re-read the freshly-truncated projection. Best-effort {@code FLUSHALL}; a failure (e.g. on
   * the very first run) is ignored.
   */
  @BeforeEach
  void flushRedis() {
    try {
      REDIS.execInContainer("redis-cli", "FLUSHALL");
    } catch (Exception ignored) {
      // best-effort; the DB truncate is the primary reset and the cache TTL is a backstop
    }
  }

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  private static Connection adminConnection() throws SQLException {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void provisionAppRole() {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement()) {
      st.execute(
          "DO $$ BEGIN "
              + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
              + APP_USER
              + "') THEN "
              + "CREATE ROLE "
              + APP_USER
              + " LOGIN PASSWORD '"
              + APP_PASSWORD
              + "'; "
              + "END IF; END $$");
      // The app role owns the schema so Flyway (running as it) can create the tables; PostgreSQL 16
      // revokes CREATE on public from PUBLIC by default.
      st.execute("GRANT ALL ON SCHEMA public TO " + APP_USER);
      st.execute("GRANT ALL ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + APP_USER);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to provision the app_user role", e);
    }
  }
}
