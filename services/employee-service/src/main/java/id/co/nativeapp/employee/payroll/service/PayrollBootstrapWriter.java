package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.messaging.CompanyCreatedEvent;
import id.co.nativeapp.events.ProcessedEventStore;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit that activates the OFFICIAL statutory dataset for a new
 * IDR company — idempotently. A distinct bean (not a private method) so the Spring proxy + auto-RLS
 * aspect engage (rule 5); the caller ({@link PayrollBootstrapService}) binds the tenant from the
 * event's {@code company_id} first.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> {@link ProcessedEventStore#processOnce} claims
 * the event UUID and runs the seed only on the FIRST delivery, in ONE transaction with the dedupe
 * claim — a re-delivery is a no-op, and a failure rolls back both the seed and the claim so the
 * at-least-once redelivery retries cleanly. The seed itself ({@link
 * PayrollSetupService#seedOfficialBootstrap}) is ALSO idempotent, so this is belt-and-suspenders: a
 * delivery that somehow slipped the dedupe still cannot double-seed.
 */
@Component
public class PayrollBootstrapWriter {

  private final ProcessedEventStore processedEvents;
  private final PayrollSetupService payrollSetupService;

  public PayrollBootstrapWriter(
      ProcessedEventStore processedEvents, PayrollSetupService payrollSetupService) {
    this.processedEvents = processedEvents;
    this.payrollSetupService = payrollSetupService;
  }

  /**
   * Activates the OFFICIAL dataset for the bound tenant exactly once per event id (must run inside
   * a {@link id.co.nativeapp.tenant.TenantContext} scope bound to the event's company_id). Reuses
   * the console setup-gate's exact composition ({@link PayrollSetupService#seedOfficialBootstrap})
   * so the go-live default has a single source of truth.
   *
   * @return {@code true} if applied (first delivery), {@code false} if a skipped duplicate.
   */
  @Transactional
  public boolean bootstrapOfficial(CompanyCreatedEvent event) {
    // Persist the canonical ISO-4217 form: the gate in PayrollBootstrapService normalises before
    // comparing, so store the same normalised value rather than the raw wire string (defensive —
    // org-service emits canonical "IDR" today).
    String baseCurrency = event.baseCurrency().strip().toUpperCase(Locale.ROOT);
    return processedEvents.processOnce(
        event.eventId(),
        () ->
            payrollSetupService.seedOfficialBootstrap(
                baseCurrency, PayrollSetupService.DEFAULT_OFFICIAL_DATASET_VERSION));
  }
}
