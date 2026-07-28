package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * The operation references a commission rule not visible for that employee (unknown id, another
 * package's, or — invisible under RLS — another tenant's). All collapse to a {@code 404}
 * (anti-enumeration). Names only the rule id, never an amount (rule 6).
 */
public class CommissionNotFoundException extends RuntimeException {

  public CommissionNotFoundException(UUID ruleId) {
    super("Commission rule " + ruleId + " not found");
  }
}
