package id.co.nativeapp.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Pushes the bound tenant into the PostgreSQL session so row-level security can enforce tenant
 * isolation as a second, database-level line of defense (CLAUDE.md rule 5 / ARCHITECTURE.md
 * "defense in depth: a forgotten {@code WHERE} can't leak across companies").
 *
 * <p>It executes
 *
 * <pre>{@code SET LOCAL app.current_tenant = '<companyId>'}</pre>
 *
 * on the transaction's connection. RLS policies on each table are written against {@code
 * current_setting('app.current_tenant', true)}, so once this runs the database only returns /
 * accepts rows for the bound company.
 *
 * <p><strong>Why {@code SET LOCAL}.</strong> {@code SET LOCAL} scopes the setting to the current
 * transaction and PostgreSQL resets it automatically at commit/rollback. On a pooled connection
 * that is essential: the setting can never leak to the next unit of work that borrows the same
 * physical connection. It does require an open transaction — outside a transaction {@code SET
 * LOCAL} has no effect — which matches Native's "every query runs in a transaction" model.
 *
 * <p><strong>Why a bound parameter, not string concatenation.</strong> The {@code companyId}
 * ultimately originates from a JWT claim; interpolating it into SQL would be an injection vector.
 * PostgreSQL does not allow a bind parameter in the {@code SET} grammar, so we use {@code
 * set_config(name, value, is_local)}, the function form, which <em>does</em> take a bound value.
 * {@code is_local} is {@code true}, giving exactly {@code SET LOCAL} semantics.
 *
 * <p>This class is connection-oriented and free of Spring transaction APIs so it is trivially
 * unit-testable; {@link RlsTransactionSynchronizer} drives it from the active Spring transaction.
 */
public class RlsConnectionInitializer {

  /**
   * The GUC (grand unified configuration) key RLS policies read via {@code
   * current_setting('app.current_tenant', true)}. The {@code app.} prefix marks it as a custom,
   * user-defined setting.
   */
  public static final String TENANT_SETTING = "app.current_tenant";

  /**
   * {@code set_config(setting, value, is_local)} with {@code is_local = true} is the function
   * equivalent of {@code SET LOCAL}, and unlike the {@code SET} statement it accepts a bound
   * parameter for the value — so the tenant id is never concatenated into SQL.
   */
  private static final String SET_LOCAL_SQL = "SELECT set_config(?, ?, true)";

  /**
   * Applies {@code SET LOCAL app.current_tenant = companyId} on the given connection. The
   * connection MUST already be inside a transaction (autoCommit = false) for the setting to outlive
   * the statement.
   *
   * @param connection the active transactional connection
   * @param companyId the bound tenant id
   * @throws SQLException if the statement fails
   */
  public void applyTenant(Connection connection, String companyId) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(companyId, "companyId");
    try (PreparedStatement ps = connection.prepareStatement(SET_LOCAL_SQL)) {
      ps.setString(1, TENANT_SETTING);
      ps.setString(2, companyId);
      ps.execute();
    }
  }
}
