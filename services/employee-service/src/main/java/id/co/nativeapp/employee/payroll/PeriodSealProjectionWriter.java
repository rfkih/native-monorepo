package id.co.nativeapp.employee.payroll;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit that projects a consumed {@code PeriodSealed} into {@link
 * PeriodSeal} — idempotently. A distinct bean (not a private method) so the Spring proxy + auto-RLS
 * aspect engage (rule 5); the caller binds the tenant from the event's {@code company_id} first.
 *
 * <p>Idempotency (rule 3): {@link ProcessedEventStore#processOnce} claims the event UUID and runs
 * the upsert only on the FIRST delivery, in ONE transaction with the write — a re-delivery is a
 * no-op.
 */
@Component
public class PeriodSealProjectionWriter {

  private final PeriodSealRepository repository;
  private final ProcessedEventStore processedEvents;

  public PeriodSealProjectionWriter(
      PeriodSealRepository repository, ProcessedEventStore processedEvents) {
    this.repository = repository;
    this.processedEvents = processedEvents;
  }

  /**
   * Applies the seal exactly once per event id (must run inside a {@link TenantContext} scope bound
   * to the event's company_id).
   *
   * @return {@code true} if applied (first delivery), {@code false} if a skipped duplicate.
   */
  @Transactional
  public boolean apply(PeriodSealedProjectedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsert(event));
  }

  private void upsert(PeriodSealedProjectedEvent event) {
    String tenant = TenantContext.require().companyId();
    Optional<PeriodSeal> existing =
        repository.findByBusinessIdAndPeriod(event.businessId(), event.period());
    if (existing.isPresent()) {
      PeriodSeal seal = existing.get();
      seal.applySealedAt(event.sealedAt());
      repository.save(seal);
      return;
    }
    PeriodSeal seal = new PeriodSeal(event.businessId(), event.period(), event.sealedAt());
    seal.setCompanyId(tenant);
    repository.save(seal);
  }
}
