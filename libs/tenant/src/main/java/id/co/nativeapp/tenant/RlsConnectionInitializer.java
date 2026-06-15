package id.co.nativeapp.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

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
   * The SECOND, OPTIONAL group GUC key (P3d SEAM 2), read by group-table RLS policies via {@code
   * current_setting('app.current_group', true)}. Set ONLY when a consolidation group is bound (a
   * group view/close, the {@code TrialBalancePublished} consumer); a normal single-tenant request
   * never sets it, so it stays unset and every group-table policy — which requires {@code
   * group_id::text = current_setting('app.current_group', true)} — matches nothing (an unset GUC is
   * NULL, or the empty string {@code ''} on a pooled connection where a prior group transaction
   * registered the GUC and {@code SET LOCAL} reverted it; the NULL-safe text equality is false
   * either way, so the clause is never true — fail closed, and crucially it never <em>errors</em>).
   * Member-table policies never reference it, so the single-tenant path is byte-identical whether
   * or not a group is bound elsewhere.
   *
   * <p><strong>Type-exactness is enforced at the bind point, not in SQL.</strong> The value written
   * here is always normalised to a CANONICAL lowercase UUID (see {@link #applyTenantAndGroup}), so
   * the policy's text comparison is effectively type-exact without needing a {@code ::uuid} cast in
   * the policy — a cast that would RAISE on the empty-string residue above and break the
   * single-tenant path on a reused connection.
   */
  public static final String GROUP_SETTING = "app.current_group";

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
   * <p>This is the UNCHANGED single-tenant path: it sets ONLY {@code app.current_tenant} and never
   * touches {@code app.current_group}. Every existing call site keeps this exact behaviour
   * (byte-identical).
   *
   * @param connection the active transactional connection
   * @param companyId the bound tenant id
   * @throws SQLException if the statement fails
   */
  public void applyTenant(Connection connection, String companyId) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(companyId, "companyId");
    setLocal(connection, TENANT_SETTING, companyId);
  }

  /**
   * Applies the GROUP-scoped binding (P3d SEAM 2): {@code SET LOCAL app.current_tenant = companyId}
   * (the group's LEAD company) AND, additively, {@code SET LOCAL app.current_group = groupId}, both
   * transaction-scoped via {@code set_config(..., true)}.
   *
   * <p>Called ONLY when a consolidation group is bound. The group GUC is set <em>in addition
   * to</em> the tenant GUC, never instead of it — group-table RLS is a CONJUNCTION ({@code group_id
   * = current_group AND company_id = current_tenant}), so both must be present and a partial
   * binding fails closed. When no group is bound, {@link #applyTenant(Connection, String)} runs
   * instead and {@code app.current_group} is never set at all (the byte-identical single-tenant
   * path).
   *
   * <p><strong>The group id is normalised to a CANONICAL UUID before it is bound</strong> (this is
   * the SINGLE bind point — the type-exactness anchor for the group policy). The policy compares
   * {@code group_id::text = current_setting('app.current_group', true)}; PostgreSQL renders a
   * {@code uuid} column as the lowercase canonical 8-4-4-4-12 form, so to make that text comparison
   * type-exact (rather than silently matching nothing for an equivalent-but-differently-spelled id
   * — uppercase, braced, or surrounded by whitespace) the bound value is parsed and re-rendered via
   * {@link UUID#fromString(String)} + {@link UUID#toString()}. A non-UUID group id is a programming
   * error here (the only caller is {@link RlsTransactionSynchronizer}, fed a validated {@link
   * TenantContext} group binding whose id originated from a {@code UUID.toString()}), so it fails
   * LOUD with {@link IllegalArgumentException} rather than binding a value the policy could never
   * match. Casting to {@code ::uuid} in the policy itself was deliberately NOT used: a pooled
   * connection reverts a once-set {@code SET LOCAL} group GUC to the empty string {@code ''}, and
   * {@code ''::uuid} RAISES, which would break the single-tenant path on a reused connection.
   *
   * @param connection the active transactional connection
   * @param companyId the bound tenant id (the group's LEAD company)
   * @param groupId the bound consolidation group id (any UUID spelling; normalised to canonical)
   * @throws SQLException if either statement fails
   * @throws IllegalArgumentException if {@code groupId} is not a valid UUID
   */
  public void applyTenantAndGroup(Connection connection, String companyId, String groupId)
      throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(groupId, "groupId");
    setLocal(connection, TENANT_SETTING, companyId);
    setLocal(connection, GROUP_SETTING, canonicalGroupId(groupId));
  }

  /**
   * Normalises a group id to its canonical lowercase UUID string, the type-exactness anchor for the
   * {@code group_id::text = current_setting('app.current_group', true)} policy comparison.
   * Validates as a side effect: a non-UUID id throws {@link IllegalArgumentException} (fail loud at
   * the bind point) rather than binding a value the policy can never match.
   */
  private static String canonicalGroupId(String groupId) {
    try {
      return UUID.fromString(groupId.strip()).toString();
    } catch (IllegalArgumentException notAUuid) {
      throw new IllegalArgumentException(
          "Group id bound to app.current_group is not a valid UUID: '" + groupId + "'", notAUuid);
    }
  }

  private void setLocal(Connection connection, String setting, String value) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(SET_LOCAL_SQL)) {
      ps.setString(1, setting);
      ps.setString(2, value);
      ps.execute();
    }
  }
}
