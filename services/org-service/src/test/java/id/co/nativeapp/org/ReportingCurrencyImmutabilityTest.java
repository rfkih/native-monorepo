package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.org.group.domain.ConsolidationGroup;
import id.co.nativeapp.org.group.repository.ConsolidationGroupRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3d SEAM 1 — a consolidation group's {@code reporting_currency} is IMMUTABLE, exactly like {@code
 * company.base_currency}. There is no public setter (a compile-time guarantee), and at the
 * persistence layer an attempt to change a persisted group's reporting currency is a NO-OP: the
 * column is mapped {@code @Column(updatable = false)}, so Hibernate omits it from the {@code
 * UPDATE} and the stored value never changes.
 *
 * <p>This mirrors {@link BaseCurrencyImmutabilityPersistenceTest}: it loads the managed entity,
 * reflectively overwrites the in-memory {@code reportingCurrency} field (simulating any code that
 * somehow mutated it), flushes, and re-reads — the stored value is pinned to its original code. All
 * work runs in the lead's own tenant scope, so RLS permits the read/flush under {@code app_user}.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(
    ReportingCurrencyImmutabilityTest.AttemptMutation.class)
class ReportingCurrencyImmutabilityTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner-a@example.co.id";

  @Autowired private AttemptMutation attemptMutation;

  @Test
  void attemptingToChangeReportingCurrencyAfterCreationIsANoOp() throws Exception {
    UUID lead = UUID.randomUUID();

    UUID groupId =
        TenantContext.callAs(lead.toString(), ACTOR, () -> attemptMutation.seedGroup(lead));

    // Attempt to mutate reporting_currency in a fresh transaction (reflectively overwrite + flush).
    TenantContext.runAs(
        lead.toString(), ACTOR, () -> attemptMutation.tryChangeReportingCurrency(groupId, "USD"));

    // Re-read: the stored reporting_currency is UNCHANGED — the attempt was ignored.
    String stored =
        TenantContext.callAs(
            lead.toString(), ACTOR, () -> attemptMutation.readReportingCurrency(groupId));
    assertThat(stored).isEqualTo("IDR");
  }

  /** A tiny {@code @Transactional} helper bean so the auto-RLS aspect sets the tenant GUC. */
  @org.springframework.stereotype.Component
  public static class AttemptMutation {

    private final ConsolidationGroupRepository repository;

    public AttemptMutation(ConsolidationGroupRepository repository) {
      this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID seedGroup(UUID lead) {
      ConsolidationGroup group = new ConsolidationGroup(lead, "Group", "IDR");
      group.setCompanyId(lead.toString());
      return repository.saveAndFlush(group).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tryChangeReportingCurrency(UUID groupId, String newCurrency) {
      ConsolidationGroup group = repository.findById(groupId).orElseThrow();
      setField(group, newCurrency);
      // updatable=false -> Hibernate omits reporting_currency from the UPDATE -> no DB change.
      repository.saveAndFlush(group);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String readReportingCurrency(UUID groupId) {
      return repository.findById(groupId).orElseThrow().getReportingCurrency();
    }

    private static void setField(ConsolidationGroup group, String value) {
      try {
        Field field = ConsolidationGroup.class.getDeclaredField("reportingCurrency");
        field.setAccessible(true);
        field.set(group, value);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
