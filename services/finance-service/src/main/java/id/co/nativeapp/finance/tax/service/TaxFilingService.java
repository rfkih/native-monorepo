package id.co.nativeapp.finance.tax.service;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the PPN filing lifecycle (Phase 4 Tax / PPN, ADR 0017) — delegates the transactional
 * work to the proxied {@link TaxFilingWriter} (file) and {@link TaxSettlementWriter} (settle).
 *
 * <p><strong>Tenant = the bound company.</strong> Like the within-company close, the return is
 * filed as the company itself — the tenant the gateway / dev filter already bound from the JWT
 * before the controller ran. So this service does not re-bind a tenant; it invokes the proxied
 * writers, which run under the already-bound company scope (RLS engages on the GL read + the {@code
 * tax_filing} write). Delegating through this thin bean (not calling the writers directly from the
 * controller) keeps the layering uniform with the rest of finance.
 */
@Service
public class TaxFilingService {

  private final TaxFilingWriter taxFilingWriter;
  private final TaxSettlementWriter taxSettlementWriter;

  public TaxFilingService(
      TaxFilingWriter taxFilingWriter, TaxSettlementWriter taxSettlementWriter) {
    this.taxFilingWriter = Objects.requireNonNull(taxFilingWriter, "taxFilingWriter");
    this.taxSettlementWriter = Objects.requireNonNull(taxSettlementWriter, "taxSettlementWriter");
  }

  /**
   * Files the PPN return for {@code (the bound company, period)}; returns the filing id + whether
   * it was freshly filed (so the controller can return 201 vs an idempotent 200).
   */
  public FileReturnResult file(String period) {
    return taxFilingWriter.file(period);
  }

  /** Settles a filed net-payable return; returns the filing id. */
  public UUID settle(UUID filingId) {
    return taxSettlementWriter.settle(filingId);
  }
}
