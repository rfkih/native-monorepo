package id.co.nativeapp.finance.companyexpense.domain;

import java.util.UUID;

/**
 * The submitted {@code business_id} is not a known outlet in the bound tenant (checked against
 * finance's local {@code org_unit_ref} read model — rule 2, never a sync call). Maps to {@code 422}
 * via {@code CompanyExpenseAdvice}.
 */
public class UnknownBusinessUnitException extends RuntimeException {

  public UnknownBusinessUnitException(UUID businessId) {
    super("unknown business unit for this company: " + businessId);
  }
}
