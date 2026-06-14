package id.co.nativeapp.employee.payroll;

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

  /** Calculate and post a payroll run. */
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

  /** Get a run's company-level summary; {@code 404} if not visible to the tenant. */
  @GetMapping("/{runId}")
  public ResponseEntity<PayrollRunResponse> getRun(@PathVariable UUID runId) {
    return payrollRunReader
        .findRun(runId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Get an employee's payslip lines for a run, MASKED (no plaintext salary on the wire). */
  @GetMapping("/{runId}/payslips/{employeeId}")
  public ResponseEntity<List<PayslipLineResponse>> getPayslip(
      @PathVariable UUID runId, @PathVariable UUID employeeId) {
    return ResponseEntity.ok(payrollRunReader.findPayslipMasked(runId, employeeId));
  }
}
