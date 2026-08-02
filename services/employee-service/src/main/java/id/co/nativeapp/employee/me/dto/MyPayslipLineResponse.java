package id.co.nativeapp.employee.me.dto;

/**
 * One line of the caller's OWN payslip — a REAL (decrypted) amount, permitted because the line
 * belongs to the caller (resolved via the employee↔login link; rule 6 stays intact for everyone
 * else's data).
 *
 * @param componentKey the pay component (BASE, COMMISSION, PPH21, …)
 * @param kind EARNING | DEDUCTION
 * @param bearer EMPLOYEE | EMPLOYER (employer-borne lines don't move the net)
 * @param amountMinor the amount in minor units
 * @param currency the ISO-4217 currency
 * @param illustrative whether the line was computed from illustrative placeholder rules
 * @param ruleVersion the statutory rule version that produced this line (null for a non-statutory
 *     line) — Track P phase P9, the printable payslip's provenance footer (HR-7 reproducibility)
 */
public record MyPayslipLineResponse(
    String componentKey,
    String kind,
    String bearer,
    long amountMinor,
    String currency,
    boolean illustrative,
    String ruleVersion) {}
