package id.co.nativeapp.employee.payroll.controller;

import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.dto.AllocationSummaryResponse;
import id.co.nativeapp.employee.payroll.dto.PayrollRunResponse;
import id.co.nativeapp.employee.payroll.dto.PayslipIndexRowResponse;
import id.co.nativeapp.employee.payroll.dto.PayslipLineResponse;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.dto.RunPayrollRequest;
import id.co.nativeapp.employee.payroll.service.PayrollRunReader;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/payroll-runs} — run payroll and read run summaries / masked payslips under the
 * bound company.
 *
 * <ul>
 *   <li>{@code POST /api/v1/payroll-runs} — calculate AND post a run (gate -&gt; freeze -&gt;
 *       compute -&gt; allocate -&gt; POSTED, emitting PayrollPosted + LaborCostAllocated); returns
 *       {@code 201} + {@code Location}.
 *   <li>{@code GET /api/v1/payroll-runs/{runId}} — the run's company-level summary; {@code 200} or
 *       {@code 404}.
 *   <li>{@code GET /api/v1/payroll-runs/{runId}/payslips/{employeeId}} — an employee's payslip
 *       lines, MASKED (PII never crosses the boundary for this endpoint).
 * </ul>
 *
 * <p>A thin HTTP adapter: maps the request to a command, calls one service method, maps the result
 * to a DTO — never an entity on the wire. PII (salary) is masked in every response (rule 6). The
 * tenant is bound at the edge, so RLS scopes every lookup and {@code company_id} is stamped from
 * that scope, never the body (rule 5).
 */
@Tag(
    name = "Payroll Runs",
    description =
        "Execute payroll runs and read run summaries and masked payslips under the bound company")
@RestController
@RequestMapping("/api/v1/payroll-runs")
public class PayrollRunController {

  private final PayrollRunService payrollRunService;
  private final PayrollRunReader payrollRunReader;

  public PayrollRunController(
      PayrollRunService payrollRunService, PayrollRunReader payrollRunReader) {
    this.payrollRunService = payrollRunService;
    this.payrollRunReader = payrollRunReader;
  }

  @Operation(
      summary = "Calculate and post a payroll run",
      description =
          "Executes the full payroll pipeline (gate -> freeze -> compute -> allocate -> POSTED)"
              + " for the requested period and employee set; returns 201 + Location. Emits"
              + " PayrollPosted and LaborCostAllocated via the outbox. Salary figures are never"
              + " exposed in events or logs (rule 6).")
  @PostMapping
  public ResponseEntity<PayrollRunResponse> runPayroll(
      @Valid @RequestBody RunPayrollRequest request) {
    RunPayrollCommand command =
        new RunPayrollCommand(
            request.period(),
            request.employeeIds(),
            request.expectedSourceBusinessIds() == null
                ? List.of()
                : request.expectedSourceBusinessIds());
    PayrollRun run = payrollRunService.calculateAndPost(command, request.baseCurrency());
    PayrollRunResponse body = PayrollRunResponse.from(run);
    return ResponseEntity.created(URI.create("/api/v1/payroll-runs/" + body.id())).body(body);
  }

  @Operation(
      summary = "List a period's payroll runs",
      description =
          "Every run for the YYYY-MM period, newest run_seq first — company totals only. A"
              + " re-run appears as an additional posting (there is no reversal).")
  @GetMapping
  public List<PayrollRunResponse> listRuns(@RequestParam(required = false) String period) {
    return payrollRunReader.listByPeriod(period);
  }

  @Operation(
      summary = "Get a run's labor cost by outlet",
      description =
          "The run's labor-cost buckets SUMmed per (outlet, GL account) across employees — the"
              + " aggregation is the privacy guard: per-employee rows would leak individual"
              + " salary (rule 6). The all-zeros outlet is the UNALLOCATED suspense bucket.")
  @GetMapping("/{runId}/allocations")
  public ResponseEntity<List<AllocationSummaryResponse>> getAllocations(@PathVariable UUID runId) {
    return payrollRunReader
        .allocationSummaries(runId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @Operation(
      summary = "Get a run's payslip index",
      description =
          "One row per employee with a payslip in the run (display name + line count) — no"
              + " amounts; fetch the masked line detail per employee separately.")
  @GetMapping("/{runId}/payslips")
  public ResponseEntity<List<PayslipIndexRowResponse>> getPayslipIndex(@PathVariable UUID runId) {
    return payrollRunReader
        .payslipIndex(runId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @Operation(
      summary = "Get a payroll run summary",
      description =
          "Returns the company-level summary for a run (totals, status, illustrative flag);"
              + " 404 if the run is not visible to the bound tenant.")
  @GetMapping("/{runId}")
  public ResponseEntity<PayrollRunResponse> getRun(@PathVariable UUID runId) {
    return payrollRunReader
        .findRun(runId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @Operation(
      summary = "Get masked payslip lines for an employee",
      description =
          "Returns the payslip lines for a single employee within a run; salary amounts are"
              + " masked — no plaintext salary crosses the boundary (rule 6).")
  @GetMapping("/{runId}/payslips/{employeeId}")
  public ResponseEntity<List<PayslipLineResponse>> getPayslip(
      @PathVariable UUID runId, @PathVariable UUID employeeId) {
    return ResponseEntity.ok(payrollRunReader.findPayslipMasked(runId, employeeId));
  }
}
