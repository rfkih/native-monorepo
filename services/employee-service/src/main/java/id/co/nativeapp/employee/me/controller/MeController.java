package id.co.nativeapp.employee.me.controller;

import id.co.nativeapp.employee.me.dto.MeProfileResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipDetailResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipHeaderResponse;
import id.co.nativeapp.employee.me.dto.MySalesResponse;
import id.co.nativeapp.employee.me.service.MeReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me} — the employee self-service surface. The caller is resolved from the
 * gateway-injected {@code X-Actor} (the JWT sub) via the employee↔login link; there is NO
 * employee-id parameter anywhere, so a caller can only ever read their own data. Routed at the
 * gateway for every business role (owner/manager/cashier/employee).
 *
 * <p>PII: NIK/bank stay masked even to the person themselves; only payslip AMOUNTS decrypt —
 * strictly the caller's own lines (rule 6 stays intact for everyone else's data).
 */
@Tag(
    name = "Me",
    description =
        "Employee self-service: own profile (PII masked), own payslips (real amounts, own rows"
            + " only)")
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

  private final MeReader meReader;

  public MeController(MeReader meReader) {
    this.meReader = meReader;
  }

  @Operation(
      summary = "My profile",
      description =
          "The caller's employee record (NIK/bank masked) with assignments and contracts. 404"
              + " with the employee-not-linked problem type when the login has no employee link.")
  @GetMapping("/profile")
  public MeProfileResponse profile() {
    return meReader.profile();
  }

  @Operation(
      summary = "My payslip index",
      description =
          "Run headers for every payroll run carrying the caller's lines, newest first — no"
              + " amounts on the list; open a run for the real figures.")
  @GetMapping("/payslips")
  public List<MyPayslipHeaderResponse> payslips(@RequestParam(required = false) String period) {
    return meReader.payslipHeaders(period);
  }

  @Operation(
      summary = "My payslip detail",
      description =
          "The caller's OWN payslip lines for one run with REAL amounts (the only decrypted"
              + " payslip read — own rows strictly). 404 when the run is unknown or carries none"
              + " of the caller's lines.")
  @GetMapping("/payslips/{runId}")
  public ResponseEntity<MyPayslipDetailResponse> payslip(@PathVariable UUID runId) {
    return meReader
        .payslipDetail(runId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @Operation(
      summary = "My sales + commission preview",
      description =
          "The caller's own summed sales for the period and, if they have an active commission,"
              + " an estimated commission (rate × sales). The estimate is a preview — the posted"
              + " payslip is authoritative.")
  @GetMapping("/sales")
  public MySalesResponse sales(@RequestParam(required = false) String period) {
    return meReader.salesSummary(period);
  }
}
