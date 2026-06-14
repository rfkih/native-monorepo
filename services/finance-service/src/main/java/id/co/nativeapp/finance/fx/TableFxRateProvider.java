package id.co.nativeapp.finance.fx;

import id.co.nativeapp.money.FxRate;
import id.co.nativeapp.money.Provenance;
import id.co.nativeapp.money.RateType;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * The default {@link FxRateProvider}: reads the GLOBAL {@code fx_rate} reference table via a plain
 * {@link JdbcTemplate}, with NO tenant predicate — exactly like {@link
 * id.co.nativeapp.finance.mapping.GlAccountResolver} reads {@code mapping_rule}.
 *
 * <p>{@code fx_rate} is global reference data (the same rate for every tenant), not Auditable and
 * not under RLS, so this reader carries no {@code WHERE company_id}. The effective-dated resolution
 * SQL mirrors {@code GlAccountResolver.lookup}: scan by {@code (from, to, rate_type)}, filter by
 * the {@code [effective_from, effective_to]} window containing the as-of date, and take the highest
 * version. The two integer rate columns are reconstructed into the {@link FxRate} value type by the
 * {@link RowMapper}, which re-validates both currency codes via {@link Currency#getInstance}.
 */
@Component
public class TableFxRateProvider implements FxRateProvider {

  private static final String RESOLVE_SQL =
      """
      SELECT from_ccy, to_ccy, rate_unscaled, rate_scale, rate_type, provenance, effective_from
        FROM fx_rate
       WHERE from_ccy = ?
         AND to_ccy = ?
         AND rate_type = ?
         AND ? BETWEEN effective_from AND effective_to
       ORDER BY version DESC
       LIMIT 1
      """;

  private final JdbcTemplate jdbcTemplate;

  public TableFxRateProvider(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public Optional<ResolvedFxRate> rateFor(
      Currency from, Currency to, RateType rateType, LocalDate asOf) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(rateType, "rateType");
    Objects.requireNonNull(asOf, "asOf");

    List<ResolvedFxRate> rates =
        jdbcTemplate.query(
            RESOLVE_SQL,
            FX_RATE_ROW_MAPPER,
            from.getCurrencyCode(),
            to.getCurrencyCode(),
            rateType.name(),
            Date.valueOf(asOf));
    return rates.isEmpty() ? Optional.empty() : Optional.of(rates.getFirst());
  }

  /**
   * Reconstructs a {@link ResolvedFxRate} (the {@link FxRate} value + its {@code effective_from})
   * from the two integer rate columns + the currency/type/provenance strings, re-validating both
   * ISO-4217 codes (PostgreSQL space-pads {@code CHAR(3)}, so strip before {@link
   * Currency#getInstance}).
   */
  private static final RowMapper<ResolvedFxRate> FX_RATE_ROW_MAPPER =
      (rs, rowNum) ->
          new ResolvedFxRate(
              new FxRate(
                  rs.getLong("rate_unscaled"),
                  rs.getInt("rate_scale"),
                  Currency.getInstance(rs.getString("from_ccy").strip()),
                  Currency.getInstance(rs.getString("to_ccy").strip()),
                  RateType.valueOf(rs.getString("rate_type")),
                  Provenance.valueOf(rs.getString("provenance"))),
              rs.getDate("effective_from").toLocalDate());
}
