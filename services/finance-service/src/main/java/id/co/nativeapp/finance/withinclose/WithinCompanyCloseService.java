package id.co.nativeapp.finance.withinclose;

import org.springframework.stereotype.Service;

/**
 * Orchestrates a company's WITHIN-COMPANY close (P3d SEAM 4a — THE PRODUCERS) — delegates the
 * transactional close to the proxied {@link WithinCompanyCloseWriter}.
 *
 * <p><strong>Tenant = the closing company, bound at the request edge.</strong> Unlike the GROUP
 * close (whose tenant is the group's lead, resolved from the read model and bound via {@link
 * id.co.nativeapp.tenant.TenantContext#callAsGroup}), the within-company close runs as the company
 * itself — the tenant the gateway / dev filter already bound from the JWT before the controller
 * ran. So this service does NOT re-bind a tenant; it simply invokes the proxied writer, which runs
 * under the already-bound company scope (RLS engages on the ledger read + the close-row write).
 */
@Service
public class WithinCompanyCloseService {

  private final WithinCompanyCloseWriter writer;

  public WithinCompanyCloseService(WithinCompanyCloseWriter writer) {
    this.writer = writer;
  }

  /**
   * Runs the within-company close for {@code (the bound company, period)}. The base currency is
   * DERIVED from the company's ledger (immutable, set at company creation — CLAUDE.md); {@code
   * declaredCurrency} is an OPTIONAL cross-check only.
   *
   * @param period the accounting period {@code YYYY-MM}
   * @param declaredCurrency an optional caller-supplied ISO-4217 currency, cross-checked against
   *     the ledger-derived base currency (may be {@code null})
   * @return the {@link WithinCompanyCloseResult}
   */
  public WithinCompanyCloseResult close(String period, String declaredCurrency) {
    return writer.close(period, declaredCurrency);
  }
}
