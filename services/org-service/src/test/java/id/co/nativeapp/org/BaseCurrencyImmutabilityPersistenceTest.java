package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.org.company.Company;
import id.co.nativeapp.org.company.CompanyRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance (b), persistence flavour — an attempt to change a persisted company's {@code
 * base_currency} is a NO-OP: the stored value is unchanged.
 *
 * <p>This forces the question past the public API (which has no setter — see {@link
 * BaseCurrencyImmutabilityTest}) down to the ORM: it loads the managed entity, reflectively
 * overwrites the in-memory {@code baseCurrency} field (simulating any code that somehow mutated
 * it), flushes, evicts, and re-reads. Because the column is mapped {@code @Column(updatable =
 * false)}, Hibernate omits it from the {@code UPDATE}, so the database value never changes. The
 * assertion pins the stored value to its original code.
 *
 * <p>All work runs inside the company's own tenant scope (a company is its own tenant), so RLS
 * permits the read/flush under the unprivileged {@code app_user}.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(
    BaseCurrencyImmutabilityPersistenceTest.AttemptMutation.class)
class BaseCurrencyImmutabilityPersistenceTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner-a@example.co.id";

  // The seed/mutate/read work is routed through a tiny @Transactional helper bean so
  // the auto-RLS aspect (which advises @Transactional methods) sets the tenant GUC; a
  // self-invocation from the test would not be proxied. The bean is supplied via
  // @Import above (a test nested @Component is not on the app's component-scan path).
  @Autowired private AttemptMutation attemptMutation;

  @Test
  void attemptingToChangeBaseCurrencyAfterCreationIsANoOp() throws Exception {
    UUID companyId = UUID.randomUUID();

    // Seed a company directly in its own tenant scope (bootstrap), bypassing the
    // service so this test isolates the immutability property.
    TenantContext.runAs(companyId.toString(), ACTOR, () -> attemptMutation.seedCompany(companyId));

    // Attempt to mutate base_currency in a fresh transaction (reflectively overwrite
    // the managed field + flush). updatable=false makes Hibernate skip the column.
    TenantContext.runAs(
        companyId.toString(), ACTOR, () -> attemptMutation.tryChangeBaseCurrency(companyId, "USD"));

    // Re-read: the stored base_currency is UNCHANGED — the attempt was ignored.
    String stored =
        TenantContext.callAs(
            companyId.toString(), ACTOR, () -> attemptMutation.readBaseCurrency(companyId));
    assertThat(stored).isEqualTo("IDR");
  }

  /**
   * A tiny {@code @Transactional} helper bean so the auto-RLS aspect (which advises
   * {@code @Transactional} methods) sets the tenant GUC for the mutate/read work — self-invocation
   * from the test would not be proxied.
   */
  @org.springframework.stereotype.Component
  public static class AttemptMutation {

    private final CompanyRepository repository;

    public AttemptMutation(CompanyRepository repository) {
      this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seedCompany(UUID companyId) {
      Company company = new Company(companyId, "Acme", "IDR", "id", companyId);
      company.setCompanyId(companyId.toString());
      repository.saveAndFlush(company);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tryChangeBaseCurrency(UUID companyId, String newCurrency) {
      Company company = repository.findById(companyId).orElseThrow();
      // Force the in-memory value to change, simulating any code that mutated it.
      setField(company, newCurrency);
      // updatable=false -> Hibernate omits base_currency from the UPDATE -> no DB change.
      repository.saveAndFlush(company);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String readBaseCurrency(UUID companyId) {
      return repository.findById(companyId).orElseThrow().getBaseCurrency();
    }

    private static void setField(Company company, String value) {
      try {
        Field field = Company.class.getDeclaredField("baseCurrency");
        field.setAccessible(true);
        field.set(company, value);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
