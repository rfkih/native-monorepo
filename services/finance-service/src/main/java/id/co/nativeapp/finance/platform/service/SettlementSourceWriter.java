package id.co.nativeapp.finance.platform.service;

import id.co.nativeapp.finance.platform.domain.SettlementSourceKind;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records who pays out a tender family (ADR 0076): "my QRIS is settled by Shopee". One row per
 * (company, kind), upserted — this is a setting, not an event log.
 *
 * <p><strong>Re-pointing moves the existing balance with it.</strong> The sub-ledger row for a
 * tender family is keyed by a STABLE channel_code, so changing the payer updates that row's
 * source_code in the same transaction rather than stranding the outstanding balance under the old
 * payer and starting a second row. Without this, switching acquirer would quietly split one
 * merchant's QRIS money across two payout groups, and the older one would never settle.
 *
 * <p>MARKETPLACE is rejected: its payer is its own channel_code by construction, and letting it be
 * configured would let the two drift apart with nothing to reconcile them.
 */
@Component
public class SettlementSourceWriter {

  private static final String UPSERT_SQL =
      """
      INSERT INTO settlement_source_config
          (id, source_kind, source_code,
           created_at, created_by, updated_at, updated_by, version, company_id)
      VALUES (?, ?, ?, now(), ?, now(), ?, 0, ?)
      ON CONFLICT (company_id, source_kind) DO UPDATE SET
          source_code = EXCLUDED.source_code,
          updated_at  = now(),
          updated_by  = EXCLUDED.updated_by,
          version     = settlement_source_config.version + 1
      """;

  /** Carries the already-accrued balance over to the new payer (see the class note). */
  private static final String REPOINT_SQL =
      """
      UPDATE platform_receivable
         SET source_code = ?,
             updated_at  = now(),
             updated_by  = ?,
             version     = version + 1
       WHERE company_id  = ?
         AND source_kind = ?
         AND source_code <> ?
      """;

  private final JdbcTemplate jdbcTemplate;

  public SettlementSourceWriter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Sets the payer for a tender family and moves that family's outstanding balance under it.
   *
   * @throws IllegalArgumentException for {@link SettlementSourceKind#MARKETPLACE}, whose payer is
   *     its channel and is never configured
   */
  @Transactional
  public void setSource(SettlementSourceKind kind, String sourceCode) {
    if (kind == SettlementSourceKind.MARKETPLACE) {
      throw new IllegalArgumentException(
          "a marketplace balance is paid out by its own channel — its source is not configurable");
    }
    String code = sourceCode == null ? "" : sourceCode.strip();
    if (code.isEmpty()) {
      throw new IllegalArgumentException("sourceCode is required");
    }
    if (code.length() > 32) {
      throw new IllegalArgumentException("sourceCode must be at most 32 chars");
    }

    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();
    String actor = tenant.actor();

    jdbcTemplate.update(UPSERT_SQL, UUID.randomUUID(), kind.name(), code, actor, actor, companyId);
    jdbcTemplate.update(REPOINT_SQL, code, actor, companyId, kind.name(), code);
  }
}
