package id.co.nativeapp.security.app;

import id.co.nativeapp.tenant.TenantContext;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant-OPTIONAL (bootstrap) endpoint under {@code /api/v1/**}, the stand-in for org-service's
 * {@code POST /api/v1/companies} create-company bootstrap. It is declared tenant-optional via
 * {@code native.security.tenant-optional} in the test {@code application.yml}, so {@code
 * TenantBindingFilter} lets an authenticated-but-tenant-less token through to this handler instead
 * of rejecting it {@code 403} — exactly the production regression the fix closes.
 *
 * <p>Like a real bootstrap, it does NOT rely on an inbound tenant scope: it must be reachable with
 * a token that carries no {@code company_id} claim. It asserts no tenant is bound on entry (the
 * filter proceeded WITHOUT binding) and then opens its OWN scope over a freshly "generated" tenant
 * id, mirroring {@code CompanyController.createCompany} → {@code CompanyService} → {@code
 * TenantContext.callAs(newCompanyId, ...)}.
 *
 * <p>It counts invocations so a test can prove a rejected (401) request NEVER reaches the handler.
 */
@RestController
public class BootstrapController {

  /** Incremented on every handler invocation; a 401 request must leave this untouched. */
  public static final AtomicInteger INVOCATIONS = new AtomicInteger();

  @PostMapping("/api/v1/bootstrap")
  public ResponseEntity<String> bootstrap() throws Exception {
    INVOCATIONS.incrementAndGet();

    // The tenant-optional path proceeds WITHOUT binding a tenant: the bootstrap creates its own.
    boolean boundOnEntry = TenantContext.isBound();

    String newTenantId = java.util.UUID.randomUUID().toString();
    String openedScopeTenant =
        TenantContext.callAs(
            newTenantId, "bootstrap-owner", () -> TenantContext.require().companyId());

    // Body proves: (1) no tenant was bound at the edge for this bootstrap call; and (2) the
    // controller successfully opened its own scope over the new id.
    return ResponseEntity.status(201)
        .body("boundOnEntry=" + boundOnEntry + ";openedScope=" + openedScopeTenant);
  }
}
