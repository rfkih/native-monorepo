package id.co.nativeapp.employee.me.controller;

import id.co.nativeapp.employee.me.dto.MeProfileResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipDetailResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipHeaderResponse;
import id.co.nativeapp.employee.me.dto.MySalesResponse;
import id.co.nativeapp.employee.me.dto.SetMyOperatorPinRequest;
import id.co.nativeapp.employee.me.service.MeReader;
import id.co.nativeapp.employee.me.service.MeWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  private static final Logger log = LoggerFactory.getLogger(MeController.class);

  private final MeReader meReader;
  private final MeWriter meWriter;

  public MeController(MeReader meReader, MeWriter meWriter) {
    this.meReader = meReader;
    this.meWriter = meWriter;
  }

  @Operation(
      summary = "My profile",
      description =
          "The caller's employee record (NIK/bank masked) with assignments and contracts. 404"
              + " with the employee-not-linked problem type when the login has no employee link.")
  @GetMapping("/profile")
  public MeProfileResponse profile() {
    // Resolve the caller first (404s an unlinked login), THEN purge any held one-time password:
    // reaching here proves the employee has authenticated, i.e. already changed it (ADR 0014).
    MeProfileResponse profile = meReader.profile();
    try {
      meWriter.purgeTempPasswordForCaller();
    } catch (RuntimeException e) {
      // Best-effort (ADR 0014) — a purge failure must never break the employee's own dashboard;
      // the TTL backstop still hides the stale value, and the next /me call retries the purge.
      log.warn("Deferred login temp-password purge (will retry on next /me call)", e);
    }
    return profile;
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

  @Operation(
      summary = "Set or change my own operator PIN",
      description =
          "Self-service set-or-change of the caller's own till operator PIN (ADR 0049 P2). No"
              + " current PIN is required — the caller is already authenticated on this surface —"
              + " so this also serves forgot-PIN. 404 with the employee-not-linked problem type"
              + " when the login has no employee link.")
  @PutMapping("/operator-pin")
  public ResponseEntity<Void> setOperatorPin(@Valid @RequestBody SetMyOperatorPinRequest request) {
    meWriter.setOwnOperatorPin(request.newPin());
    return ResponseEntity.noContent().build();
  }
}
