package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryReader;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cross-tenant isolation of every expense-claims table (V9: {@code expense_category}, {@code
 * expense_claim}, {@code expense_claim_event}) via the auto-applied RLS (rule 5). Tenant A creates
 * a category and a claim (through submit); tenant B, in its own scope, sees NEITHER — the
 * manager-facing list, the single-claim read, and the category list all come back empty/404. No
 * manual {@code WHERE company_id} anywhere — purely RLS (Postgres Testcontainers, non-superuser app
 * role).
 */
@SpringBootTest
class ExpenseClaimRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR_A = "aaaaaaaa-1111-1111-1111-111111111111";
  private static final String ACTOR_B = "bbbbbbbb-2222-2222-2222-222222222222";

  @Autowired private EmployeeService employeeService;
  @Autowired private ExpenseCategoryWriter categoryWriter;
  @Autowired private ExpenseCategoryReader categoryReader;
  @Autowired private ExpenseClaimService claimService;
  @Autowired private ExpenseClaimReader claimReader;

  @Test
  void tenantBCannotSeeTenantAsExpenseCategoryOrClaim() throws Exception {
    UUID claimId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Budi", "TK0", "3201234567890123", "1234567890123456"))
                      .getId();
              employeeService.linkUser(employeeId, ACTOR_A, null);

              ExpenseCategory category = categoryWriter.create("Supplies", "supplies", false);

              return claimService
                  .create(
                      new CreateClaimCommand(
                          category.getId(),
                          250_000L,
                          "IDR",
                          LocalDate.of(2026, 7, 15),
                          "Warung Makan",
                          "lunch",
                          null))
                  .getId();
            });

    // A sees its own category + claim.
    List<?> categoriesForA = TenantContext.callAs(TENANT_A, ACTOR_A, categoryReader::list);
    assertThat(categoriesForA).isNotEmpty();
    assertThat(TenantContext.callAs(TENANT_A, ACTOR_A, () -> claimReader.one(claimId))).isNotNull();
    assertThat(TenantContext.callAs(TENANT_A, ACTOR_A, () -> claimReader.forManager(null, null)))
        .hasSize(1);

    // B, in its own scope, sees NEITHER (RLS fail-closed).
    List<?> categoriesForB = TenantContext.callAs(TENANT_B, ACTOR_B, categoryReader::list);
    assertThat(categoriesForB).isEmpty();
    assertThat(TenantContext.callAs(TENANT_B, ACTOR_B, () -> claimReader.forManager(null, null)))
        .isEmpty();
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR_B, () -> claimReader.one(claimId)))
        .isInstanceOf(ClaimNotFoundException.class);
  }
}
