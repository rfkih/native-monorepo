package id.co.nativeapp.finance.inventory.service;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Answers "is the bound tenant perpetual-inventory-ACTIVE for accounting period {@code P}?" (ADR
 * 0067 §5) — the single decision every Phase B/C/D consumer and {@code BillWriter} branch on.
 *
 * <p><strong>The DORMANT invariant (ADR 0067 Phase B).</strong> An ACTIVE {@code
 * inventory_method_config} row EXISTS for the tenant whose {@code cutover_period <= P} → active; NO
 * row (the state for EVERY tenant in Phase B — the table ships empty, V53) ⇒ INACTIVE. A {@code
 * NULL cutover_period} (not yet set — Phase D's activation flow populates it) is treated as "no
 * gate", i.e. active as soon as the row exists, matching ADR 0067 §5's "new companies:
 * perpetual-active from creation" posture. Reads under RLS (no explicit {@code company_id}
 * predicate needed — the bound tenant's row, if any, is the only one visible; {@code
 * uq_inventory_method_config_company} guarantees at most one).
 *
 * <p>Annotated {@code @Component} (not {@code @Service}) — mirrors {@link
 * id.co.nativeapp.finance.gl.service.RoleAccountResolver}, a global-reference-data resolver, not an
 * orchestrating service — to satisfy the ArchUnit naming rule (a {@code @Service} must be named
 * {@code *Service}/{@code *Reader}; this class already ends in {@code Reader} so either annotation
 * would pass, but {@code @Component} keeps the two helpers consistent).
 */
@Component
public class PerpetualInventoryReader {

  private static final String SQL =
      "SELECT perpetual_active, cutover_period FROM inventory_method_config";

  private final JdbcTemplate jdbcTemplate;

  public PerpetualInventoryReader(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  /**
   * @param period the accounting period ({@code YYYY-MM}) the event/posting targets
   * @return {@code true} only when an ACTIVE row exists whose cutover has been reached
   */
  public boolean isActiveFor(String period) {
    Objects.requireNonNull(period, "period");
    List<Row> rows =
        jdbcTemplate.query(
            SQL,
            (rs, rowNum) ->
                new Row(rs.getBoolean("perpetual_active"), rs.getString("cutover_period")));
    if (rows.isEmpty()) {
      return false;
    }
    Row row = rows.get(0);
    if (!row.perpetualActive()) {
      return false;
    }
    return row.cutoverPeriod() == null || row.cutoverPeriod().compareTo(period) <= 0;
  }

  private record Row(boolean perpetualActive, String cutoverPeriod) {}
}
