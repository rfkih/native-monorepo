package id.co.nativeapp.employee.payroll;

/**
 * Response DTO for a single payslip line. The line {@code amount} is individual salary — PII (rule
 * 6) — so it is MASKED for a non-authorized caller ({@code amountMasked = "***"}, {@code
 * amountMinor = null}); an authorized HR role / the employee's own worker surface receives the
 * decrypted minor amount + currency. The component key, kind, bearer, GL account, stamped {@code
 * ruleVersion}, and {@code illustrative} flag are non-PII and always present. A test asserts no
 * plaintext salary crosses the controller boundary for an unauthorized caller (HR-6).
 */
public record PayslipLineResponse(
    String componentKey,
    String kind,
    String bearer,
    String glAccount,
    String amountMasked,
    Long amountMinor,
    String currency,
    String ruleVersion,
    boolean illustrative) {

  /** The MASKED projection (non-authorized caller): no minor amount, only the structural fields. */
  public static PayslipLineResponse masked(PayslipLine line) {
    return new PayslipLineResponse(
        line.getComponentKey(),
        line.getKind().name(),
        line.getBearer().name(),
        line.getGlAccount(),
        "***",
        null,
        null,
        line.getRuleVersion(),
        line.isIllustrative());
  }

  /** The AUTHORIZED projection (employee's own surface / HR role): the decrypted minor amount. */
  public static PayslipLineResponse authorized(PayslipLine line) {
    return new PayslipLineResponse(
        line.getComponentKey(),
        line.getKind().name(),
        line.getBearer().name(),
        line.getGlAccount(),
        null,
        line.getAmount().amountMinor(),
        line.getAmount().currency().getCurrencyCode(),
        line.getRuleVersion(),
        line.isIllustrative());
  }
}
