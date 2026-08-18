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
 * <p><strong>Provenance.</strong> Each {@code role_account_map} version also carries {@code
 * uses_illustrative} — {@code true} for an SME-gated placeholder mapping, {@code false} for an
 * SME-verified OFFICIAL one. {@link #resolveWithProvenance} surfaces it so a role-based posting
 * writer can DERIVE its journal entry's {@code uses_illustrative_rules} flag from the mapping
 * versions it actually resolved, rather than hardcoding it. This is the {@code role_account_map}
 * analogue of the {@code posting_template.uses_illustrative} the SALE path already reads: once
 * every role resolves an OFFICIAL version, a new posting is no longer badged provisional (the
 * illustrative placeholder rows stay as effective-dated history and never re-flag a later posting).
 *
 * <p>Annotated {@code @Component} (not {@code @Service}) to satisfy the ArchUnit naming rule.
 */
@Component
public class RoleAccountResolver {

  /** The suspense account used when an AccountRole has no seeded mapping. */
  public static final String SUSPENSE_ACCOUNT_CODE = "9999";

  /**
   * A resolved role → account mapping plus whether that mapping VERSION is illustrative (an
   * SME-gated placeholder) rather than an SME-verified OFFICIAL figure. {@code accountCode} is
   * {@code null} when no mapping is seeded, in which case {@code illustrative} is {@code true} — an
   * unmapped role routes to SUSPENSE and its posting is provisional (money is never dropped, HR-3).
   */
  public record ResolvedAccount(String accountCode, boolean illustrative) {}

  private static final String RESOLVE_SQL =
      """
      SELECT gl_account_code, uses_illustrative
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
   * Resolves {@code role} at {@code occurredAt} to its account code AND the provenance of the
   * mapping version actually used (highest {@code version} whose window contains {@code
   * occurredAt}). Returns {@code (null, true)} when no mapping is seeded — an unmapped role routes
   * to SUSPENSE and is treated as illustrative (unverified).
   *
   * @param role the semantic role to resolve
   * @param occurredAt the event's occurred-at instant (drives the effective version)
   */
  public ResolvedAccount resolveWithProvenance(AccountRole role, Instant occurredAt) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(occurredAt, "occurredAt");
    LocalDate asOf = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
    List<ResolvedAccount> rows =
        jdbcTemplate.query(
            RESOLVE_SQL,
            (rs, rowNum) ->
                new ResolvedAccount(
                    rs.getString("gl_account_code"), rs.getBoolean("uses_illustrative")),
            role.name(),
            Date.valueOf(asOf));
    return rows.isEmpty() ? new ResolvedAccount(null, true) : rows.getFirst();
  }

  /**
   * Returns the {@code chart_of_account.account_code} for {@code role} at {@code occurredAt}, or
   * {@code null} if no mapping is seeded / effective.
   *
   * @param role the semantic role to resolve
   * @param occurredAt the event's occurred-at instant (drives the effective version)
   */
  public String resolve(AccountRole role, Instant occurredAt) {
    return resolveWithProvenance(role, occurredAt).accountCode();
  }

  /**
   * Whether ANY of {@code roles} resolves to an illustrative mapping (or is unmapped) at {@code
   * occurredAt} — the {@code uses_illustrative_rules} flag a role-based journal entry must carry so
   * the dashboard / statements provisional badge reflects the ACTUAL provenance of the accounts
   * posted, not a hardcoded assumption. {@code false} once every supplied role resolves an OFFICIAL
   * mapping version.
   *
   * @param occurredAt the event's occurred-at instant (drives the effective version per role)
   * @param roles the roles this entry actually posts to
   */
  public boolean anyIllustrative(Instant occurredAt, AccountRole... roles) {
    for (AccountRole role : roles) {
      if (resolveWithProvenance(role, occurredAt).illustrative()) {
        return true;
      }
    }
    return false;
  }
}
