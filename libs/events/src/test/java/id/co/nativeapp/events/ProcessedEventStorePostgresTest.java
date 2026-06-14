package id.co.nativeapp.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The {@link ProcessedEventStore} idempotency tests run against a real PostgreSQL 16.
 *
 * <p>This is deliberate: the dedupe contract depends on {@code INSERT ... ON CONFLICT DO NOTHING}
 * semantics, which H2 does not implement, and on PostgreSQL's "a unique-violation aborts the whole
 * transaction" behaviour, which H2's auto-commit fixture cannot reproduce. Testing idempotency on
 * H2 was the blind spot that let the original transaction-poisoning bug through, so these tests use
 * the genuine engine.
 */
@Testcontainers
class ProcessedEventStorePostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate tx;
  private ProcessedEventStore store;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    ds.setDriverClassName("org.postgresql.Driver");
    this.jdbcTemplate = new JdbcTemplate(ds);
    this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    jdbcTemplate.execute("DROP TABLE IF EXISTS processed_event");
    jdbcTemplate.execute(
        "CREATE TABLE processed_event ("
            + "event_id UUID PRIMARY KEY, "
            + "processed_at TIMESTAMP WITH TIME ZONE NOT NULL)");
    this.store = new ProcessedEventStore(jdbcTemplate);
  }

  @Test
  void processOnceRunsHandlerOnlyForTheFirstDelivery() {
    UUID eventId = UUID.randomUUID();
    AtomicInteger sideEffects = new AtomicInteger();

    boolean firstRan = tx.execute(s -> store.processOnce(eventId, sideEffects::incrementAndGet));
    boolean secondRan = tx.execute(s -> store.processOnce(eventId, sideEffects::incrementAndGet));

    assertTrue(firstRan, "first delivery must run the handler");
    assertFalse(secondRan, "re-delivery must be skipped");
    assertEquals(1, sideEffects.get(), "handler side effect must happen exactly once");
  }

  @Test
  void alreadyProcessedReflectsState() {
    UUID eventId = UUID.randomUUID();
    assertFalse(store.alreadyProcessed(eventId));

    tx.execute(s -> store.processOnce(eventId, () -> {}));

    assertTrue(store.alreadyProcessed(eventId));
  }

  @Test
  void distinctEventIdsEachProcessOnce() {
    AtomicInteger sideEffects = new AtomicInteger();
    boolean firstRan =
        tx.execute(s -> store.processOnce(UUID.randomUUID(), sideEffects::incrementAndGet));
    boolean secondRan =
        tx.execute(s -> store.processOnce(UUID.randomUUID(), sideEffects::incrementAndGet));
    assertTrue(firstRan);
    assertTrue(secondRan);
    assertEquals(2, sideEffects.get());
  }

  @Test
  void redeliveryInASeparateTransactionIsACleanNoOp() {
    UUID eventId = UUID.randomUUID();
    AtomicInteger sideEffects = new AtomicInteger();

    Boolean first = tx.execute(status -> store.processOnce(eventId, sideEffects::incrementAndGet));

    // Re-delivery in its own transaction: it must be skipped AND the transaction must stay
    // healthy enough to keep doing work afterwards (a naive catch-exception impl would have
    // aborted this transaction at the duplicate insert).
    Boolean second =
        tx.execute(
            status -> {
              boolean ran = store.processOnce(eventId, sideEffects::incrementAndGet);
              Integer count =
                  jdbcTemplate.queryForObject(
                      "SELECT count(*) FROM processed_event", Integer.class);
              assertEquals(1, count, "a duplicate claim must not insert a second row");
              return ran;
            });

    assertEquals(Boolean.TRUE, first, "first delivery runs the handler");
    assertEquals(Boolean.FALSE, second, "re-delivery is skipped");
    assertEquals(1, sideEffects.get(), "the side effect happens exactly once");
  }

  @Test
  void duplicateClaimDoesNotAbortTheTransaction() {
    UUID eventId = UUID.randomUUID();
    tx.execute(status -> store.processOnce(eventId, () -> {})); // first claim, committed

    // In a single live transaction: a duplicate claim, then more work that MUST succeed.
    String result =
        tx.execute(
            status -> {
              boolean ran = store.processOnce(eventId, () -> {});
              assertFalse(ran, "the duplicate must be skipped");
              // Proves the transaction is still usable (not aborted by the dup).
              return jdbcTemplate.queryForObject("SELECT 'tx-alive'", String.class);
            });

    assertEquals("tx-alive", result, "transaction must remain usable after a duplicate claim");
  }
}
