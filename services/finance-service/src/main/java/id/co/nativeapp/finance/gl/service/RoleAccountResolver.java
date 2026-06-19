package id.co.nativeapp.finance.gl.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves an {@link AccountRole} to a concrete {@code chart_of_account.account_code} at a given
 * instant, reading the versioned, effective-dated {@code role_account_map} global-reference table
 * (V13 — NOT Auditable, NOT RLS — the same global-ref pattern as {@code mapping_rule}).
 *
 * <p><strong>Resolution.</strong> Picks the highest {@code version} whose {@code [effective_from,
 * effective_to]} window contains {@code occurredAt} (date in UTC), exactly mirroring {@code
 * GlAccountResolver}. Returns {@code null} when no mapping is seeded — the caller routes to the
 * SUSPENSE account and stamps {@code uses_illustrative_rules=true} (money is never dropped; HR-3).
 *
 * <p>Annotated {@code @Component} (not {@code @Service}) to satisfy the ArchUnit naming rule.
 */
@Component
public class RoleAccountResolver {

  /** The suspense account used when an AccountRole has no seeded mapping. */
  public static final String SUSPENSE_ACCOUNT_CODE = "9999";

  private static final String RESOLVE_SQL =
      """
      SELECT gl_account_code
        FROM role_account_map
       WHERE account_role = ?
         AND ? BETWEEN effective_from AND effective_to
       ORDER BY version DESC, effective_from DESC
       LIMIT 1
      """;

  private final JdbcTemplate jdbcTemplate;

  public RoleAccountResolver(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  /**
   * Returns the {@code chart_of_account.account_code} for {@code role} at {@code occurredAt}, or
   * {@code null} if no mapping is seeded / effective.
   *
   * @param role the semantic role to resolve
   * @param occurredAt the event's occurred-at instant (drives the effective version)
   */
  public String resolve(AccountRole role, Instant occurredAt) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(occurredAt, "occurredAt");
    LocalDate asOf = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
    List<String> codes =
        jdbcTemplate.query(
            RESOLVE_SQL,
            (rs, rowNum) -> rs.getString("gl_account_code"),
            role.name(),
            Date.valueOf(asOf));
    return codes.isEmpty() ? null : codes.getFirst();
  }
}
