package id.co.nativeapp.org.company.controller;

import id.co.nativeapp.org.company.dto.OutletResponse;
import id.co.nativeapp.org.company.service.OrgUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/outlets} — the POS outlet picker's data source.
 *
 * <p>Returns active {@code OUTLET}-type org units for the bound company, ordered by name, as a slim
 * {@code [{id, name}]} list. The POS uses this to populate the outlet picker before opening a sale.
 * An empty list is returned (not {@code 204}) so the POS can distinguish "no outlets configured"
 * from a network error.
 *
 * <p>This endpoint is intentionally on a separate path from {@code /api/v1/org-units} so the
 * gateway can expose it to {@code cashier} (POS role surface) while keeping the full org-tree
 * endpoint owner/manager-only (dashboard surface).
 *
 * <p>Tenant-scoped: the gateway/{@code DevTenantFilter} binds the tenant from the validated JWT
 * before the handler runs; RLS scopes every lookup to the bound company (rule 5).
 */
@Tag(name = "Outlets", description = "POS outlet picker — active outlets for the bound company")
@RestController
@RequestMapping("/api/v1/outlets")
public class OutletController {

  private final OrgUnitService orgUnitService;

  public OutletController(OrgUnitService orgUnitService) {
    this.orgUnitService = orgUnitService;
  }

  /**
   * Returns active outlets for the bound company, ordered by name. Used by the POS outlet picker to
   * populate the outlet selection before opening a sale. An empty list ({@code []}) is returned
   * when no active outlets exist — the POS treats this as "no outlets, hide picker" (not a {@code
   * 204}, which the POS cannot distinguish from a network error).
   *
   * <p>RLS scopes the read to the bound company (rule 5); no {@code WHERE company_id} is in the
   * query.
   */
  @Operation(
      summary = "List active outlets (POS outlet picker)",
      description =
          "Returns active OUTLET-type org units for the bound company, ordered by name."
              + " Used by the POS outlet picker. 200 always (empty list when no active outlets"
              + " exist — the POS treats empty as 'no outlets, hide picker'). RLS-scoped (rule 5).")
  @GetMapping
  public ResponseEntity<List<OutletResponse>> listActiveOutlets() {
    return ResponseEntity.ok(orgUnitService.listActiveOutlets());
  }
}
