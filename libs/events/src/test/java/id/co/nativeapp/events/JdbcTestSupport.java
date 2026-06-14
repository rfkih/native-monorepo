package id.co.nativeapp.events;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared H2 (PostgreSQL compatibility mode) fixture for the libs/events tests.
 *
 * <p>Each test gets a fresh in-memory database with the outbox/processed_event DDL applied, plus a
 * {@link JdbcTemplate}, a {@link TransactionTemplate}, and the collaborators under test wired to
 * that database. Using PostgreSQL mode keeps the SQL (UUID, BYTEA, TIMESTAMP WITH TIME ZONE)
 * faithful to the production PostgreSQL 16 target.
 */
abstract class JdbcTestSupport {

  private DataSource dataSource;
  protected JdbcTemplate jdbcTemplate;
  protected TransactionTemplate transactionTemplate;
  protected OutboxWriter outboxWriter;
  protected ProcessedEventStore processedEventStore;
  protected StubRelay stubRelay;

  @BeforeEach
  void setUpDatabase() {
    // A uniquely-named in-memory DB per test, in PostgreSQL compatibility mode.
    String url =
        "jdbc:h2:mem:events_"
            + System.nanoTime()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    DriverManagerDataSource ds = new DriverManagerDataSource(url, "sa", "");
    ds.setDriverClassName("org.h2.Driver");
    this.dataSource = ds;
    this.jdbcTemplate = new JdbcTemplate(ds);
    this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(ds));

    jdbcTemplate.execute(loadDdl());

    this.outboxWriter = new OutboxWriter(jdbcTemplate);
    this.processedEventStore = new ProcessedEventStore(jdbcTemplate);
    this.stubRelay = new StubRelay(jdbcTemplate);
  }

  @AfterEach
  void tearDownDatabase() {
    // DB_CLOSE_DELAY=-1 keeps the DB alive until the JVM exits; drop it explicitly so each
    // test is fully isolated.
    jdbcTemplate.execute("DROP ALL OBJECTS");
  }

  protected long outboxRowCount() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Long.class);
    return count == null ? 0L : count;
  }

  private static String loadDdl() {
    try (InputStream in =
        JdbcTestSupport.class.getClassLoader().getResourceAsStream("outbox-ddl.sql")) {
      if (in == null) {
        throw new IllegalStateException("outbox-ddl.sql not found on the test classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read outbox-ddl.sql", e);
    }
  }
}
