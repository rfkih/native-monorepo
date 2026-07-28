package id.co.nativeapp.employee.payroll.dto;

/**
 * The tenant's payroll-setup status ({@code GET /api/v1/payroll-setup}) — whether the pay-component
 * catalog + statutory rules exist and what their provenance is. The console gates the Payroll tab
 * on {@code seeded} and shows the loud illustrative banner whenever {@code provenance} is not
 * {@code OFFICIAL}.
 *
 * @param seeded whether the tenant's pay-component catalog exists
 * @param componentCount the number of pay components in the catalog
 * @param provenance ILLUSTRATIVE_PLACEHOLDER | OFFICIAL | MIXED, or null when nothing is seeded
 * @param illustrativeVersion the illustrative rule-version label when illustrative rules exist
 *     (e.g. {@code ILLUSTRATIVE-2026.1}), else null
 */
public record PayrollSetupResponse(
    boolean seeded, long componentCount, String provenance, String illustrativeVersion) {}
