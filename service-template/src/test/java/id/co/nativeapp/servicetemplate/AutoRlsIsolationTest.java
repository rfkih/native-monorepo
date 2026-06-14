package id.co.nativeapp.servicetemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.servicetemplate.widget.Widget;
import id.co.nativeapp.servicetemplate.widget.WidgetService;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance (c) — the MANDATORY negative auto-RLS proof (the M0.2 item both reviewers deferred).
 *
 * <p>This test never calls {@code RlsTransactionSynchronizer.applyToCurrentTransaction()} or {@code
 * RlsConnectionInitializer.applyTenant(...)}; it never adds a {@code WHERE company_id = ...}
 * clause. It relies <strong>only</strong> on {@link RlsAutoApplyAspect} setting the tenant GUC on
 * every {@code @Transactional} unit of work. That models a developer who writes a repository method
 * and simply forgets the tenant — and proves they are still protected: inside tenant A's scope the
 * repository returns only A's rows and cannot see B's, and vice versa.
 *
 * <p>It runs as the unprivileged {@code app_user} role (see {@link PostgresRlsTestBase}); the
 * {@code FORCE ROW LEVEL SECURITY} in the baseline binds even that owning role, so the policy
 * genuinely enforces.
 */
@SpringBootTest
class AutoRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "company-A";
  private static final String TENANT_B = "company-B";
  private static final String ACTOR_A = "actor-a";
  private static final String ACTOR_B = "actor-b";

  @Autowired private WidgetService widgetService;

  @Test
  void autoAppliedRlsIsolatesTenantsWithoutAnyManualApply() throws Exception {
    // Seed two rows for A and one for B — each create runs in its own tenant
    // scope, and the auto-applied GUC + WITH CHECK guarantee each row lands
    // under the right company. No manual applyTenant here either.
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          widgetService.create("a-1");
          widgetService.create("a-2");
          return null;
        });
    TenantContext.callAs(TENANT_B, ACTOR_B, () -> widgetService.create("b-1"));

    // Inside A's scope: repository.findAll() carries NO tenant filter; only the
    // automatic RLS restricts the result to A's rows.
    List<String> aNames =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> widgetService.findAll().stream().map(Widget::getName).sorted().toList());
    assertThat(aNames).containsExactly("a-1", "a-2");

    // The inverse: inside B's scope only B's row is visible.
    List<String> bNames =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () -> widgetService.findAll().stream().map(Widget::getName).sorted().toList());
    assertThat(bNames).containsExactly("b-1");

    // Counts confirm neither tenant can see the other's rows.
    long aCount = TenantContext.callAs(TENANT_A, ACTOR_A, widgetService::count);
    long bCount = TenantContext.callAs(TENANT_B, ACTOR_B, widgetService::count);
    assertThat(aCount).isEqualTo(2L);
    assertThat(bCount).isEqualTo(1L);
  }

  /**
   * Fail-closed proof (the security-review gap): a {@code @Transactional} unit of work run with NO
   * {@link TenantContext} scope must not leak data. The aspect sets no GUC, the policy keyed to
   * {@code current_setting('app.current_tenant', true)} matches nothing, so reads come back EMPTY
   * rather than exposing every tenant; and a write fails loudly because {@code
   * WidgetService.create} calls {@link TenantContext#require()}.
   */
  @Test
  void withNoTenantScopeReadsAreEmptyAndWritesFailLoudly() throws Exception {
    // There IS data in the table (owned by A) that must stay invisible without a scope.
    TenantContext.callAs(TENANT_A, ACTOR_A, () -> widgetService.create("a-only"));

    // No scope at all: RLS fails closed — empty reads, not a cross-tenant leak.
    assertThat(widgetService.findAll()).isEmpty();
    assertThat(widgetService.count()).isZero();

    // A write with no bound tenant must fail fast, not be silently mis-attributed.
    assertThatThrownBy(() -> widgetService.create("orphan"))
        .isInstanceOf(IllegalStateException.class);
  }
}
