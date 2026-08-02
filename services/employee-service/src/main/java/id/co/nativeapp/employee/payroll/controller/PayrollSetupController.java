package id.co.nativeapp.employee.payroll.controller;

import id.co.nativeapp.employee.payroll.dto.OverrideStatutoryRuleRequest;
import id.co.nativeapp.employee.payroll.dto.PayrollSetupResponse;
import id.co.nativeapp.employee.payroll.dto.SeedIllustrativeRequest;
import id.co.nativeapp.employee.payroll.dto.SeedOfficialRequest;
import id.co.nativeapp.employee.payroll.dto.SeedOfficialResponse;
import id.co.nativeapp.employee.payroll.dto.StatutoryRuleDetailResponse;
import id.co.nativeapp.employee.payroll.dto.StatutoryRuleResponse;
import id.co.nativeapp.employee.payroll.service.PayrollSetupReader;
import id.co.nativeapp.employee.payroll.service.PayrollSetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

  @Operation(
      summary = "List the tenant's statutory rules",
      description =
          "Every statutory rule row for the bound tenant (identity, calc type, provenance,"
              + " effective range, source note) — no params, newest effective_from first per"
              + " rule_key. The console badges ILLUSTRATIVE_PLACEHOLDER amber and OFFICIAL green.")
  @GetMapping("/rules")
  public List<StatutoryRuleResponse> listRules() {
    return reader.listRules();
  }

  @Operation(
      summary = "Get a statutory rule's full detail",
      description =
          "The rule_key's currently-open row INCLUDING its params JSON — the override dialog's"
              + " source of truth. 404 if the rule_key was never seeded for this tenant.")
  @GetMapping("/rules/{ruleKey}")
  public ResponseEntity<StatutoryRuleDetailResponse> getRule(@PathVariable String ruleKey) {
    return reader
        .ruleDetail(ruleKey)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @Operation(
      summary = "Activate an OFFICIAL statutory dataset",
      description =
          "Activates a canned, versioned OFFICIAL dataset (e.g. ID-2026.1) for the bound tenant:"
              + " inserts its rules (closing any overlapping prior row of the same rule_key) and"
              + " upserts its pay-component catalog additions. Idempotent — a re-run over the same"
              + " version is a no-op. 404 for an unknown dataset version; 409 if the tenant has no"
              + " statutory rule on file yet (seed-illustrative first).")
  @PostMapping("/seed-official")
  public SeedOfficialResponse seedOfficial(@Valid @RequestBody SeedOfficialRequest request) {
    return service.seedOfficial(request.datasetVersion());
  }

  @Operation(
      summary = "Override a statutory rule with a new effective-dated row",
      description =
          "The human-verification path (ADR 0031's activation checklist): creates a NEW"
              + " effective-dated row for rule_key, superseding the currently-open one — never an"
              + " in-place edit, so an already-frozen run stays byte-identically reproducible."
              + " Params are validated through the existing row's calc-family parser; provenance"
              + " may flip ILLUSTRATIVE_PLACEHOLDER -> OFFICIAL (e.g. once PMK 168/2023's TER"
              + " Lampiran is transcribed verbatim). 404 if rule_key was never seeded; 400 if"
              + " effectiveFrom is not after the current row's effectiveFrom or params fail to"
              + " parse.")
  @PatchMapping("/rules/{ruleKey}")
  public StatutoryRuleDetailResponse overrideRule(
      @PathVariable String ruleKey, @Valid @RequestBody OverrideStatutoryRuleRequest request) {
    return service.overrideRule(ruleKey, request);
  }
}
