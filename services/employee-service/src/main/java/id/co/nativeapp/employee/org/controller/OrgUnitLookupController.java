package id.co.nativeapp.employee.org.controller;

import id.co.nativeapp.employee.org.dto.OrgUnitLookupResponse;
import id.co.nativeapp.employee.org.service.OrgUnitLookupReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/employees/org-units?ids=} — the local org-read-model lookup the console uses
 * to resolve an org unit's {@code legal_employer_id} before creating an employment contract (rule
 * 2: no synchronous call to org-service; employee-service consumes the org events into this
 * projection).
 *
 * <p>URL-space note: the literal {@code org-units} segment deliberately lives under {@code
 * /api/v1/employees} so the gateway's single employee-service route covers it; Spring's pattern
 * ranking (literal beats variable) keeps it from being swallowed by {@code GET /{employeeId}} —
 * pinned by {@code OrgUnitLookupEndpointTest}.
 */
@Tag(
    name = "Org-unit lookup",
    description =
        "Resolve org units (legal employer, type, active) from the locally consumed org read"
            + " model; RLS silently drops ids outside the bound tenant")
@RestController
@RequestMapping("/api/v1/employees/org-units")
public class OrgUnitLookupController {

  private final OrgUnitLookupReader reader;

  public OrgUnitLookupController(OrgUnitLookupReader reader) {
    this.reader = reader;
  }

  @Operation(
      summary = "Look up org units from the local read model",
      description =
          "Returns {orgUnitId, legalEmployerId, type, active} for each requested id the bound"
              + " tenant can see; unknown or foreign ids are silently absent (anti-enumeration).")
  @GetMapping
  public List<OrgUnitLookupResponse> lookup(@RequestParam(required = false) List<UUID> ids) {
    // Optional at the binding layer so a missing param surfaces as the reader's
    // IllegalArgumentException -> a clean RFC-7807 400 (not the catch-all 500).
    return reader.lookup(ids);
  }
}
