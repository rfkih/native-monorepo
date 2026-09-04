package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.messaging.CompanyCreatedEvent;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * On {@code CompanyCreated}, auto-activates the OFFICIAL statutory dataset for a NEW Indonesian
 * company so its payroll is OFFICIAL from creation (ADR 0042 go-live default, made automatic).
 * Binds the tenant from the event's {@code company_id} (no JWT on the consumer path) and delegates
 * to the proxied {@link PayrollBootstrapWriter} so the {@code @Transactional} advice + auto-RLS
 * aspect engage under that tenant — the same Service/Writer split as {@code
 * PeriodSealProjectionService} / {@code PeriodSealProjectionWriter}.
 *
 * <p><strong>Indonesia gate.</strong> The canned OFFICIAL dataset ({@code ID-2026.1}: BPJS, PTKP,
 * PPh21 TER) is Indonesian statutory law. Native is multi-country (ADR 0059), so only a company
 * whose base currency is {@code IDR} — the established Indonesia proxy (ADR 0025: country ID → IDR)
 * — is auto-seeded; a non-IDR company is skipped, so it is never seeded a wrong-jurisdiction
 * ruleset (and, having no rules, correctly shows no illustrative badge).
 */
@Service
public class PayrollBootstrapService {

  /** The audit actor stamped into {@code created_by} for this system-driven consumer write. */
  public static final String CONSUMER_ACTOR = "employee-payroll-bootstrap-consumer";

  /** The base-currency proxy for "this company is in Indonesia" (ADR 0025). */
  private static final String INDONESIA_BASE_CURRENCY = "IDR";

  private static final Logger log = LoggerFactory.getLogger(PayrollBootstrapService.class);

  private final PayrollBootstrapWriter writer;

  public PayrollBootstrapService(PayrollBootstrapWriter writer) {
    this.writer = writer;
  }

  /**
   * Handles one decoded {@code CompanyCreated}. For an IDR company, activates the OFFICIAL dataset
   * for the new tenant (idempotent by event id). For a non-IDR company, does nothing.
   *
   * @return {@code true} if this delivery activated the official dataset (first delivery of an IDR
   *     company), {@code false} if skipped (non-IDR, or an already-processed re-delivery).
   */
  public boolean onCompanyCreated(CompanyCreatedEvent event) {
    if (!isIndonesian(event.baseCurrency())) {
      log.debug(
          "Skipping payroll auto-bootstrap for companyId={} baseCurrency={} (non-IDR; the"
              + " Indonesian statutory dataset does not apply)",
          event.companyId(),
          event.baseCurrency());
      return false;
    }
    try {
      return TenantContext.callAs(
          event.companyId(), CONSUMER_ACTOR, () -> writer.bootstrapOfficial(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to auto-bootstrap official payroll on CompanyCreated", e);
    }
  }

  private static boolean isIndonesian(String baseCurrency) {
    return baseCurrency != null
        && INDONESIA_BASE_CURRENCY.equals(baseCurrency.strip().toUpperCase(Locale.ROOT));
  }
}
