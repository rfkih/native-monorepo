package id.co.nativeapp.finance.gl.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.PostingTemplate;
import id.co.nativeapp.finance.gl.domain.TemplateLine;
import id.co.nativeapp.finance.gl.domain.TemplateLine.Side;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves the EFFECTIVE {@link PostingTemplate} for an {@link EventKind} at a given instant,
 * reading the versioned, effective-dated {@code posting_template} / {@code posting_template_line}
 * global-reference tables (V13 — NOT Auditable, NOT RLS — the same global-ref pattern as {@code
 * mapping_rule}).
 *
 * <p><strong>Resolution.</strong> Picks the highest {@code version} whose {@code [effective_from,
 * effective_to]} window contains {@code occurredAt} (date in UTC), exactly mirroring {@code
 * GlAccountResolver}. A missing template returns {@code null}; the caller falls back to a suspense
 * entry and stamps {@code uses_illustrative_rules=true}.
 *
 * <p>Annotated {@code @Component} (not {@code @Service}) to satisfy the ArchUnit naming rule:
 * {@code springServicesAreNamedService} allows only {@code *Service} and {@code *Reader} to carry
 * {@code @Service}; this resolver is a {@code @Component}.
 */
@Component
public class PostingTemplateResolver {

  /**
   * Selects the posting_template header row: highest version whose effective window contains the
   * occurred date.
   */
  private static final String TEMPLATE_SQL =
      """
      SELECT id, version, uses_illustrative
        FROM posting_template
       WHERE event_kind = ?
         AND ? BETWEEN effective_from AND effective_to
       ORDER BY version DESC
       LIMIT 1
      """;

  /**
   * Selects ordered lines for the selected template (joined to the template id returned above). A
   * separate query because the line set is small (2–4 rows) and avoids a GROUP-BY/aggregate.
   */
  private static final String LINES_SQL =
      """
      SELECT line_no, account_role, side, amount_basis
        FROM posting_template_line
       WHERE template_id = ?
       ORDER BY line_no
      """;

  private final JdbcTemplate jdbcTemplate;

  public PostingTemplateResolver(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  /**
   * Returns the effective {@link PostingTemplate} for {@code eventKind} at {@code occurredAt}, or
   * {@code null} if no template is seeded / effective at that date.
   *
   * @param eventKind the event kind to resolve (SALE, EXPENSE, LABOR)
   * @param occurredAt the event's occurred-at instant (drives the effective version)
   */
  public PostingTemplate resolve(EventKind eventKind, Instant occurredAt) {
    Objects.requireNonNull(eventKind, "eventKind");
    Objects.requireNonNull(occurredAt, "occurredAt");
    LocalDate asOf = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();

    // Step 1 — find the effective template header.
    List<Object[]> headers =
        jdbcTemplate.query(
            TEMPLATE_SQL,
            (rs, rowNum) ->
                new Object[] {
                  rs.getObject("id", java.util.UUID.class),
                  rs.getInt("version"),
                  rs.getBoolean("uses_illustrative")
                },
            eventKind.name(),
            Date.valueOf(asOf));

    if (headers.isEmpty()) {
      return null;
    }

    Object[] header = headers.getFirst();
    java.util.UUID templateId = (java.util.UUID) header[0];
    int version = (int) header[1];
    boolean usesIllustrative = (boolean) header[2];

    // Step 2 — load the ordered lines for this template.
    List<TemplateLine> lines =
        jdbcTemplate.query(
            LINES_SQL,
            (rs, rowNum) -> {
              int lineNo = rs.getInt("line_no");
              AccountRole role = AccountRole.valueOf(rs.getString("account_role").toUpperCase());
              Side side = Side.valueOf(rs.getString("side").toUpperCase());
              String amountBasis = rs.getString("amount_basis");
              return new TemplateLine(lineNo, role, side, amountBasis);
            },
            templateId);

    return new PostingTemplate(eventKind, version, usesIllustrative, lines);
  }
}
