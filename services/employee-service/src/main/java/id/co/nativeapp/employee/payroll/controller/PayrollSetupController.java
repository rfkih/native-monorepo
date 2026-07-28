package id.co.nativeapp.employee.payroll.controller;

import id.co.nativeapp.employee.payroll.dto.PayrollSetupResponse;
import id.co.nativeapp.employee.payroll.dto.SeedIllustrativeRequest;
import id.co.nativeapp.employee.payroll.service.PayrollSetupReader;
import id.co.nativeapp.employee.payroll.service.PayrollSetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/payroll-setup} — the console's payroll bootstrap surface.
 *
 * <ul>
 *   <li>{@code GET} — whether the tenant's pay-component catalog + statutory rules exist and their
 *       provenance (the console gates the Payroll tab on it and banners any non-OFFICIAL
 *       provenance).
 *   <li>{@code POST /seed-illustrative} — seed the ILLUSTRATIVE PLACEHOLDER catalog + rules in the
 *       tenant's base currency (idempotent; NOT verified DJP/BPJS figures — every run over them is
 *       flagged {@code usesIllustrativeRules} and the console shows a loud banner).
 * </ul>
 */
@Tag(
    name = "Payroll setup",
    description =
        "Bootstrap and inspect the tenant's pay-component catalog + statutory rules (illustrative"
            + " placeholder figures until OFFICIAL rows are seeded)")
@RestController
@RequestMapping("/api/v1/payroll-setup")
public class PayrollSetupController {

  private final PayrollSetupReader reader;
  private final PayrollSetupService service;

  public PayrollSetupController(PayrollSetupReader reader, PayrollSetupService service) {
    this.reader = reader;
    this.service = service;
  }

  @Operation(
      summary = "Get the tenant's payroll-setup status",
      description =
          "Whether the pay-component catalog + statutory rules exist, the component count, and"
              + " the rule provenance (ILLUSTRATIVE_PLACEHOLDER | OFFICIAL | MIXED).")
  @GetMapping
  public PayrollSetupResponse status() {
    return reader.status();
  }

  @Operation(
      summary = "Seed the illustrative payroll setup",
      description =
          "Seeds the default pay-component catalog + ILLUSTRATIVE PLACEHOLDER statutory rules for"
              + " the bound tenant in its base currency. Idempotent. The figures are NOT verified"
              + " DJP/BPJS rates; replace with OFFICIAL effective-dated rows before real payroll.")
  @PostMapping("/seed-illustrative")
  public PayrollSetupResponse seedIllustrative(
      @Valid @RequestBody SeedIllustrativeRequest request) {
    return service.seedIllustrative(request.baseCurrency());
  }
}
