package id.co.nativeapp.employee.payroll.controller;

import id.co.nativeapp.employee.payroll.service.PayrollReportReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/payroll-reports} — the statutory CSV exports (Track P phase P9): the annual
 * per-employee 1721-A1, the monthly SPT Masa PPh 21 aggregate summary, and the per-employee BPJS
 * contribution summary. Every export streams {@code text/csv} (like {@link
 * id.co.nativeapp.employee.payroll.controller.BankFileController}) built by {@link
 * PayrollReportReader}, which decrypts/aggregates in the service layer — never a full entity or a
 * plaintext PII field crosses this controller.
 *
 * <p><strong>Role gate is at the GATEWAY, not here</strong> (this controller carries no server-side
 * role check — mirroring {@code BankFileController}): {@code 1721a1} and {@code bpjs-summary} are
 * OWNER-ONLY routes (bank-file-style PII/salary-revealing gate); {@code pph21-monthly} is the
 * ordinary owner/manager DASHBOARD_ROLES route (an aggregate with no per-employee figure). See
 * {@code RoutingConfig} in the gateway module.
 */
@Tag(
    name = "Payroll Reports",
    description = "Statutory CSV exports: 1721-A1, SPT Masa PPh21 monthly summary, BPJS summary.")
@RestController
@RequestMapping("/api/v1/payroll-reports")
@Validated
public class PayrollReportController {

  private static final String PERIOD_PATTERN = "\\d{4}-(0[1-9]|1[0-2])";
  private static final String YEAR_PATTERN = "\\d{4}";

  private final PayrollReportReader payrollReportReader;

  public PayrollReportController(PayrollReportReader payrollReportReader) {
    this.payrollReportReader = payrollReportReader;
  }

  @Operation(
      summary = "Annual Bukti Potong 1721-A1 CSV, per employee (owner-only)",
      description =
          "NIK/NPWP decrypted at this boundary ONLY, never logged (rule 6). Illustrative layout —"
              + " not the certified DJP e-Bupot XML schema; verify before filing.")
  @GetMapping(value = "/1721a1", produces = "text/csv")
  public ResponseEntity<String> bukti1721A1(
      @RequestParam @Pattern(regexp = YEAR_PATTERN, message = "year must be a 4-digit YYYY value") String year) {
    String csv = payrollReportReader.bukti1721A1(year);
    return csvResponse(csv, "1721-A1_" + year + ".csv");
  }

  @Operation(
      summary = "Monthly SPT Masa PPh 21 aggregate summary CSV (owner/manager)",
      description =
          "AGGREGATE ONLY — gross bruto, PPh21 withheld, headcount, no-NPWP count; no per-employee"
              + " figure. Illustrative layout — verify before filing.")
  @GetMapping(value = "/pph21-monthly", produces = "text/csv")
  public ResponseEntity<String> pph21Monthly(
      @RequestParam
          @Pattern(regexp = PERIOD_PATTERN, message = "period must be a valid YYYY-MM month") String period) {
    String csv = payrollReportReader.pph21Monthly(period);
    return csvResponse(csv, "pph21-monthly-" + period + ".csv");
  }

  @Operation(
      summary = "Monthly BPJS contribution summary CSV, per employee per program (owner-only)",
      description =
          "Per-employee wage/EE/ER for Kesehatan, JHT, JP, JKK, JKM — owner-only (per-employee wage"
              + " is salary-revealing). Illustrative layout — not a certified BPJS/SIPP schema.")
  @GetMapping(value = "/bpjs-summary", produces = "text/csv")
  public ResponseEntity<String> bpjsSummary(
      @RequestParam
          @Pattern(regexp = PERIOD_PATTERN, message = "period must be a valid YYYY-MM month") String period) {
    String csv = payrollReportReader.bpjsSummary(period);
    return csvResponse(csv, "bpjs-summary-" + period + ".csv");
  }

  private static ResponseEntity<String> csvResponse(String csv, String filename) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .body(csv);
  }
}
