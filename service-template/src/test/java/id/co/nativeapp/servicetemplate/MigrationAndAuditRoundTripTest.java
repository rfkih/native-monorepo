package id.co.nativeapp.servicetemplate;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.servicetemplate.widget.domain.Widget;
import id.co.nativeapp.servicetemplate.widget.service.WidgetService;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance (b): the Flyway baseline applies against a real PostgreSQL 16 and the sample {@link
 * Widget} entity round-trips with its audit columns populated.
 *
 * <p>The full context boots (so {@link RlsAutoApplyAspect} is active, Hibernate {@code
 * ddl-auto=validate} verifies the mapping against the migrated schema, and Flyway has run). All
 * work happens inside a {@link TenantContext} scope; the persisted row's {@code created_by} comes
 * from that scope's actor (via {@code libs/tenant}'s {@code TenantAuditorAware}) and {@code
 * company_id} from its tenant — proving the audit + tenancy stamping is wired end to end.
 */
@SpringBootTest
class MigrationAndAuditRoundTripTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "company-A";
  private static final String ACTOR_A = "user-a@example.co.id";

  @Autowired private WidgetService widgetService;

  @Test
  void migrationAppliesAndEntityRoundTripsWithAuditColumns() throws Exception {
    Long id =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> widgetService.create("first-widget").getId());
    assertThat(id).isNotNull();

    List<Widget> widgets = TenantContext.callAs(TENANT_A, ACTOR_A, widgetService::findAll);

    assertThat(widgets).hasSize(1);
    Widget loaded = widgets.getFirst();
    assertThat(loaded.getName()).isEqualTo("first-widget");
    // Audit columns populated automatically from the tenant scope.
    assertThat(loaded.getCompanyId()).isEqualTo(TENANT_A);
    assertThat(loaded.getCreatedBy()).isEqualTo(ACTOR_A);
    assertThat(loaded.getUpdatedBy()).isEqualTo(ACTOR_A);
    assertThat(loaded.getCreatedAt()).isNotNull();
    assertThat(loaded.getUpdatedAt()).isNotNull();
    assertThat(loaded.getVersion()).isZero();
  }
}
