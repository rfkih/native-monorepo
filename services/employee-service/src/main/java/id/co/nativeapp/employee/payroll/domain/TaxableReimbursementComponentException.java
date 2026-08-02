package id.co.nativeapp.employee.payroll.domain;

/**
 * The {@code EXPENSE_REIMBURSEMENT} {@link PayComponent} is marked {@code taxable=true} in the
 * catalog (P7 review W2) — a misconfiguration: ADR 0030/0032 REQUIRE this component to be
 * non-taxable (the claim was already recognized at approval; taxing the reimbursement AGAIN would
 * both mistax the employee and corrupt the {@code NET_WAGES_PAYABLE} split's identity, which
 * assumes the reimbursement never enters the PPh21/BPJS tax base). Checked ONCE per run, at
 * rule-resolution time, regardless of whether the run actually has a reimbursement to apply this
 * period — a catalog integrity problem should surface immediately, not silently wait for the first
 * affected run. Mapped to {@code 422} by {@code EmployeeApiAdvice}.
 */
public class TaxableReimbursementComponentException extends RuntimeException {

  public TaxableReimbursementComponentException(String componentKey) {
    super(
        "pay_component '"
            + componentKey
            + "' (the expense-claim reimbursement earning) is marked taxable=true — it MUST be"
            + " non-taxable (ADR 0030 §6, ADR 0032 §P7 addendum); fix the catalog before running"
            + " payroll");
  }
}
