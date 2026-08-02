package id.co.nativeapp.employee.payroll.controller;

import id.co.nativeapp.employee.payroll.service.BankFileReader;
import id.co.nativeapp.employee.payroll.service.BankFileResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/payroll-runs/{runId}/bank-file} — the net-pay bank file for a POSTED payroll
 * run (Track P phase P5): a CSV of employee_name/bank_account/net_amount_minor/currency/reference,
 * for the payroll admin to hand to their bank. OWNER-ONLY at the gateway (bank-account PII, the
 * {@code /payroll-reports/1721a1} precedent) — the ONLY place the decrypted bank account crosses a
 * boundary (rule 6, {@code BankFileReader}). A fresh controller (not folded into {@link
 * id.co.nativeapp.employee.payroll.controller.PayrollRunController}) because the response is raw
 * {@code text/csv}, not a DTO.
 */
@Tag(name = "Payroll Bank File", description = "Net-pay bank file export for a POSTED payroll run.")
@RestController
@RequestMapping("/api/v1/payroll-runs")
public class BankFileController {

  private final BankFileReader bankFileReader;

  public BankFileController(BankFileReader bankFileReader) {
    this.bankFileReader = bankFileReader;
  }

  @Operation(
      summary = "The net-pay bank file for a POSTED payroll run (CSV, owner-only)",
      description =
          "Streams a CSV (employee_name, bank_account, net_amount_minor, currency, reference) for"
              + " the run's net pay, decrypting each employee's bank account at this boundary ONLY."
              + " 404 if the run is not visible to the bound tenant; 409 if it has not reached"
              + " POSTED.")
  @GetMapping(value = "/{runId}/bank-file", produces = "text/csv")
  public ResponseEntity<String> bankFile(@PathVariable UUID runId) {
    BankFileResult result = bankFileReader.generate(runId);
    String filename = "payroll-" + result.period() + "-seq" + result.runSeq() + ".csv";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .body(result.csv());
  }
}
