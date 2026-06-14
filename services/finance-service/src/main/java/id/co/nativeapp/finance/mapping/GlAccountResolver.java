package id.co.nativeapp.finance.mapping;

import id.co.nativeapp.finance.revenue.PostingType;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves a posting criterion to a {@code chart_of_account} account via the VERSIONED,
 * EFFECTIVE-DATED {@code mapping_rule} reference data (#21) — the "resolve on write" half of the
 * CQRS split (ARCHITECTURE.md finance-service: "resolve/post on write, aggregate on read").
 *
 * <p><strong>Resolution.</strong> Given a posting type, a {@code gl_hint}, and the event's {@code
 * occurred_at}, it selects the {@code mapping_rule} whose {@code [effective_from, effective_to]}
 * window contains the occurred date (the open-ended window ends at the {@code 9999-12-31}
 * sentinel), taking the highest {@code version} if more than one matches. So an event dated
 * <em>before</em> a new rule version's boundary resolves to the prior account and one dated
 * <em>after</em> it to the new account — the effective-dated behaviour proven by the boundary test.
 *
 * <p><strong>REVENUE</strong> ignores the hint (it resolves under the empty-criterion sentinel
 * {@code gl_hint = ''}).
 *
 * <p><strong>EXPENSE</strong> resolution depends on the hint:
 *
 * <ul>
 *   <li>a <em>blank</em> hint (the vertical sent no category) → the EXPENSE default catch-all rule
 *       ({@code gl_hint = ''}, the General Expense account). This is a known, expected mapping.
 *   <li>a <em>specific</em> (non-blank) hint that matches a rule → that rule's account.
 *   <li>a <em>specific</em> hint that matches NO rule → genuinely unmappable: the producer asked
 *       for a category finance does not recognise. Resolution FAILS SAFE to the SUSPENSE account
 *       ({@link #SUSPENSE_ACCOUNT_CODE}) — the expense is still posted (money is never silently
 *       dropped, HR-3) but quarantined for an operator to reclassify. It deliberately does NOT fall
 *       through to the default catch-all, so an unknown category surfaces as a real mapping gap
 *       rather than being silently absorbed into General Expense.
 * </ul>
 *
 * <p>The {@link GlAccountResolution#mapped()} flag is {@code false} only in the suspense case.
 *
 * <p>The {@code mapping_rule} / {@code chart_of_account} tables are global reference data (not
 * Auditable, not RLS), so this reader carries no tenant predicate and reads them with a plain
 * {@code JdbcTemplate}; it embeds no account codes (the mapping is data, not hardcoded —
 * ENGINEERING- STANDARDS §7). The sole code constant here is the suspense account, which is a
 * deliberate fail-safe sink rather than a business GL mapping.
 */
@Component
public class GlAccountResolver {

  /** The empty-criterion sentinel: REVENUE rules and the EXPENSE default catch-all key on it. */
  public static final String ANY_HINT = "";

  /**
   * The suspense account an unmappable EXPENSE posting lands on — money is posted to the books, not
   * dropped, but flagged for reclassification. Seeded in {@code chart_of_account} by V2.
   */
  public static final String SUSPENSE_ACCOUNT_CODE = "9999";

  private static final String RESOLVE_SQL =
      """
      SELECT gl_account_code
        FROM mapping_rule
       WHERE posting_type = ?
         AND gl_hint = ?
         AND ? BETWEEN effective_from AND effective_to
       ORDER BY version DESC
       LIMIT 1
      """;

  private final JdbcTemplate jdbcTemplate;

  public GlAccountResolver(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  /**
   * Resolves the REVENUE account effective at {@code occurredAt}. The hint is irrelevant for
   * revenue, so this keys on the empty-criterion sentinel.
   *
   * @throws IllegalStateException if no effective REVENUE rule exists (a misconfigured/un-seeded
   *     chart — revenue is a core path, so fail loudly rather than route revenue to suspense)
   */
  public String resolveRevenue(Instant occurredAt) {
    String account = lookup(PostingType.REVENUE, ANY_HINT, occurredAt);
    if (account == null) {
      throw new IllegalStateException(
          "No effective REVENUE mapping_rule for occurredAt=" + occurredAt);
    }
    return account;
  }

  /**
   * Resolves the EXPENSE account effective at {@code occurredAt} for a {@code gl_hint}: a blank
   * hint → the EXPENSE default catch-all; a specific hint with a matching rule → that account; a
   * specific hint with NO matching rule → the SUSPENSE account (fail safe). The returned {@link
   * GlAccountResolution#mapped()} is {@code false} only in the suspense case.
   *
   * @param glHint the expense hint carried on the event (may be blank)
   * @param occurredAt when the expense occurred (drives the effective rule version)
   */
  public GlAccountResolution resolveExpense(String glHint, Instant occurredAt) {
    String hint = glHint == null ? ANY_HINT : glHint.strip();

    if (hint.isEmpty()) {
      // No category from the vertical: use the EXPENSE default catch-all (General Expense).
      String fallback = lookup(PostingType.EXPENSE, ANY_HINT, occurredAt);
      if (fallback != null) {
        return new GlAccountResolution(fallback, true);
      }
      // Not even a default catch-all is seeded — unmappable. Fail safe to suspense.
      return new GlAccountResolution(SUSPENSE_ACCOUNT_CODE, false);
    }

    String specific = lookup(PostingType.EXPENSE, hint, occurredAt);
    if (specific != null) {
      return new GlAccountResolution(specific, true);
    }
    // A specific category finance does not recognise: genuinely unmappable. Route to suspense so
    // the money is posted (never dropped), flagged unmapped for reclassification — NOT silently
    // absorbed into the default General Expense account.
    return new GlAccountResolution(SUSPENSE_ACCOUNT_CODE, false);
  }

  private String lookup(PostingType postingType, String glHint, Instant occurredAt) {
    LocalDate asOf = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
    List<String> codes =
        jdbcTemplate.query(
            RESOLVE_SQL,
            (rs, rowNum) -> rs.getString("gl_account_code"),
            postingType.name(),
            glHint,
            Date.valueOf(asOf));
    return codes.isEmpty() ? null : codes.getFirst();
  }
}
