package id.co.nativeapp.employee.payroll.dto;

/**
 * Response body for {@code POST /api/v1/payroll-setup/seed-official} — the idempotent activation
 * summary. A re-run over the SAME dataset version is a no-op: every {@code *Skipped}/unchanged
 * count rises to match, {@code rulesInserted}/{@code rulesClosed}/{@code componentsInserted}/{@code
 * componentsUpdated} all stay at zero.
 *
 * @param rulesInserted new {@code statutory_rule} rows inserted (one per dataset rule not already
 *     seeded at that exact {@code rule_key + rule_version})
 * @param rulesClosed prior overlapping rows superseded ({@link
 *     id.co.nativeapp.employee.payroll.domain.StatutoryRule#supersede})
 * @param rulesSkipped dataset rules already seeded at that exact version — untouched
 * @param componentsInserted new {@code pay_component} catalog rows inserted
 * @param componentsUpdated existing {@code pay_component} rows whose fields changed to match the
 *     dataset (e.g. PPh21's rewire to {@code PPH21_TER})
 */
public record SeedOfficialResponse(
    String datasetVersion,
    int rulesInserted,
    int rulesClosed,
    int rulesSkipped,
    int componentsInserted,
    int componentsUpdated) {}
