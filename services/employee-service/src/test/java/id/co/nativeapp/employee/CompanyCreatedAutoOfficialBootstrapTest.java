package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.dto.PayrollSetupResponse;
import id.co.nativeapp.employee.payroll.messaging.CompanyCreatedEvent;
import id.co.nativeapp.employee.payroll.service.PayrollBootstrapService;
import id.co.nativeapp.employee.payroll.service.PayrollSetupReader;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Fix 1 (payroll auto-official on go-live): a {@code CompanyCreated} for an IDR company activates
 * the OFFICIAL statutory dataset for the new tenant from creation, so its resolvable provenance is
 * {@code OFFICIAL} (no illustrative rule resolves) — the whole point: no tenant is left on
 * illustrative rules going forward, so a subsequent run is never flagged {@code
 * uses_illustrative_rules = true}. A non-IDR company is skipped (the Indonesian statutory dataset
 * does not apply; Native is multi-country, ADR 0059). Redelivery of the same event id is idempotent
 * (rule 3 / HR-3).
 *
 * <p>Reads status through the {@link PayrollSetupReader} ({@code @Transactional}) — NOT the raw
 * repository — so the RLS tenant GUC is bound (the aspect fires on {@code @Transactional} beans
 * only); a raw repository read inside {@code callAs} runs unbound and RLS fails closed to empty.
 */
@SpringBootTest
class CompanyCreatedAutoOfficialBootstrapTest extends PostgresRlsTestBase {

  private static final String TENANT_IDR = "33333333-3333-3333-3333-333333333333";
  private static final String TENANT_USD = "44444444-4444-4444-4444-444444444444";
  private static final String ACTOR = PayrollBootstrapService.CONSUMER_ACTOR;

  @Autowired private PayrollBootstrapService bootstrapService;
  @Autowired private PayrollSetupReader setupReader;

  @Test
  void idrCompanyIsAutoActivatedToOfficialAndIsIdempotent() throws Exception {
    UUID eventId = UUID.randomUUID();
    CompanyCreatedEvent event = new CompanyCreatedEvent(eventId, TENANT_IDR, "IDR");

    boolean firstDelivery = bootstrapService.onCompanyCreated(event);
    assertThat(firstDelivery).as("first delivery of an IDR company activates official").isTrue();

    PayrollSetupResponse afterFirst = TenantContext.callAs(TENANT_IDR, ACTOR, setupReader::status);
    assertThat(afterFirst.seeded()).as("the pay-component catalog was seeded").isTrue();
    assertThat(afterFirst.provenance())
        .as("the tenant's resolvable statutory rules are OFFICIAL — no illustrative rule resolves")
        .isEqualTo("OFFICIAL");

    // Redelivery of the SAME event id is a no-op: skipped, and the setup is unchanged (no duplicate
    // catalog components inserted).
    boolean redelivery = bootstrapService.onCompanyCreated(event);
    assertThat(redelivery).as("re-delivery of the same event id is idempotent").isFalse();

    PayrollSetupResponse afterRedelivery =
        TenantContext.callAs(TENANT_IDR, ACTOR, setupReader::status);
    assertThat(afterRedelivery.provenance()).isEqualTo("OFFICIAL");
    assertThat(afterRedelivery.componentCount())
        .as("re-delivery did not duplicate the catalog")
        .isEqualTo(afterFirst.componentCount());
  }

  @Test
  void nonIdrCompanyIsSkipped() throws Exception {
    boolean bootstrapped =
        bootstrapService.onCompanyCreated(
            new CompanyCreatedEvent(UUID.randomUUID(), TENANT_USD, "USD"));
    assertThat(bootstrapped).as("a non-IDR company is not auto-bootstrapped").isFalse();

    PayrollSetupResponse status = TenantContext.callAs(TENANT_USD, ACTOR, setupReader::status);
    assertThat(status.seeded()).as("a non-IDR company gets no pay-component catalog").isFalse();
    assertThat(status.provenance()).as("a non-IDR company has no statutory rules").isNull();
  }
}
