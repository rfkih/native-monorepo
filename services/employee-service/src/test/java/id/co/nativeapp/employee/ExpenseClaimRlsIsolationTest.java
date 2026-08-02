package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.dto.OrgUnitExpenseSummaryResponse;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryReader;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cross-tenant isolation of every expense-claims table (V9: {@code expense_category}, {@code
 * expense_claim}, {@code expense_claim_event}) via the auto-applied RLS (rule 5). Tenant A creates
 * a category, a claim, and SUBMITS it (so an {@code expense_claim_event} audit row actually exists
 * — data-engineer review: a prior version of this test claimed "through submit" coverage without
 * ever calling submit, so the event table's RLS was never exercised); tenant B, in its own scope,
 * sees NONE of it — the manager-facing list, the single-claim read, the category list, and the
 * event-table replay probe all come back empty/404. No manual {@code WHERE company_id} anywhere —
 * purely RLS (Postgres Testcontainers, non-superuser app role).
 */
@SpringBootTest
class ExpenseClaimRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR_A = "aaaaaaaa-1111-1111-1111-111111111111";
  private static final String ACTOR_B = "bbbbbbbb-2222-2222-2222-222222222222";
  private static final String SUBMIT_IDEMPOTENCY_KEY = "idem-key-a-1";

  @Autowired private EmployeeService employeeService;
  @Autowired private ExpenseCategoryWriter categoryWriter;
  @Autowired private ExpenseCategoryReader categoryReader;
  @Autowired private ExpenseClaimService claimService;
  @Autowired private ExpenseClaimReader claimReader;

  @Test
  void tenantBCannotSeeTenantAsExpenseCategoryClaimOrClaimEvent() throws Exception {
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

              UUID createdClaimId =
                  claimService
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
              // Submit so an expense_claim_event audit row actually exists — RLS on that table
              // is otherwise never exercised (data-engineer review).
              claimService.submit(createdClaimId, SUBMIT_IDEMPOTENCY_KEY);
              return createdClaimId;
            });

    // A sees its own category + claim + submit event.
    List<?> categoriesForA = TenantContext.callAs(TENANT_A, ACTOR_A, categoryReader::list);
    assertThat(categoriesForA).isNotEmpty();
    assertThat(TenantContext.callAs(TENANT_A, ACTOR_A, () -> claimReader.one(claimId))).isNotNull();
    assertThat(
            TenantContext.callAs(
                    TENANT_A, ACTOR_A, () -> claimReader.forManager(null, null, null, null))
                .content())
        .hasSize(1);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim_event WHERE claim_id = ? AND"
                    + " idempotency_key = ? AND action = 'SUBMIT'",
                claimId,
                SUBMIT_IDEMPOTENCY_KEY))
        .isEqualTo(1L);

    // B, in its own scope, sees NONE of it (RLS fail-closed).
    List<?> categoriesForB = TenantContext.callAs(TENANT_B, ACTOR_B, categoryReader::list);
    assertThat(categoriesForB).isEmpty();
    assertThat(
            TenantContext.callAs(
                    TENANT_B, ACTOR_B, () -> claimReader.forManager(null, null, null, null))
                .content())
        .isEmpty();
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR_B, () -> claimReader.one(claimId)))
        .isInstanceOf(ClaimNotFoundException.class);
    assertThat(
            countAsTenant(
                TENANT_B,
                "SELECT count(*) FROM expense_claim_event WHERE claim_id = ? AND"
                    + " idempotency_key = ? AND action = 'SUBMIT'",
                claimId,
                SUBMIT_IDEMPOTENCY_KEY))
        .isZero();
  }

  /**
   * (W1 + W2, E8 review) Real-Postgres proof of {@code ExpenseClaimReader#summary} against the
   * fixed reconciliation gap: {@code byCategory} (the figure the org-hub tile presents as
   * reconciling to the GL) must filter on {@code approved_at}'s UTC month, NOT {@code
   * expense_date}'s — a claim incurred one month and approved a LATER month must land in the
   * APPROVAL month's category total, not the expense month's. {@code byStatus} stays on {@code
   * expense_date} (the deliberately different operational dimension) — the SAME claim's row shows
   * under the EXPENSE month there, proving the two queries genuinely answer different questions
   * rather than both having silently flipped to the same column.
   *
   * <p>The SAME real-SQL round trip also exercises (mirroring {@code
   * tenantBCannotSeeTenantAsExpenseCategoryClaimOrClaimEvent}'s {@code findForManager} coverage,
   * W2): the {@code CAST(:period AS text)} typing (a real bind parameter, not a Java branch), the
   * sentinel single-element scope list (called with {@code orgUnitIds = null}), the {@code
   * to_char(... AT TIME ZONE 'UTC', 'YYYY-MM')} grouping, the snake_case-alias-to-camelCase-getter
   * projection mapping, and — the RLS proof itself — that tenant B's {@code GROUP BY} produces
   * NOTHING for tenant A's claim under the identical period/scope tenant A just matched (RLS scopes
   * the aggregate query itself, not merely a subsequent {@code findById} check).
   *
   * <p>Deliberately does NOT stub/override the {@link java.time.Clock} bean (none is fixed for this
   * {@code @SpringBootTest} context — {@code TimeConfig} wires {@code Clock.systemUTC()}); the
   * expense date is fixed far enough in the past ({@code 2020-01-15}) that its own {@code YYYY-MM}
   * can never coincide with "now" (the real approval instant this test produces), and the
   * assertions below read the ACTUAL {@code approved_at} the write path stamped rather than
   * predicting it — so the test is deterministic without controlling the clock.
   */
  @Test
  void summaryReconcilesOnApprovedAtPeriodNotExpenseDateAndIsRlsScoped() throws Exception {
    LocalDate expenseDate = LocalDate.of(2020, 1, 15);
    String expenseDatePeriod = "2020-01";
    String managerActorA = "manager-not-linked-to-any-employee-summary-a";

    UUID claimId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Sari", "TK0", "3201234567890124", "1234567890123457"))
                      .getId();
              employeeService.linkUser(employeeId, ACTOR_A, null);

              ExpenseCategory category = categoryWriter.create("Travel", "cogs", false);

              UUID createdClaimId =
                  claimService
                      .create(
                          new CreateClaimCommand(
                              category.getId(),
                              300_000L,
                              "IDR",
                              expenseDate,
                              "Garuda",
                              "flight",
                              null))
                      .getId();
              claimService.submit(createdClaimId, "idem-summary-submit-a");
              return createdClaimId;
            });

    // Approved by a login with NO linked employee record (self-approval only trips when the
    // approver resolves to the claim's own employee — ExpenseClaimWriter#approve).
    ExpenseClaim approved =
        TenantContext.callAs(
            TENANT_A,
            managerActorA,
            () -> claimService.approve(claimId, "ok", "idem-summary-approve-a"));

    String approvedAtPeriod =
        DateTimeFormatter.ofPattern("yyyy-MM")
            .withZone(ZoneOffset.UTC)
            .format(approved.getApprovedAt());
    // Sanity: the two periods genuinely differ for this claim, or the assertions below would be
    // vacuously true regardless of which column the query actually filters on.
    assertThat(approvedAtPeriod).isNotEqualTo(expenseDatePeriod);

    // (W1) byCategory reconciles on approved_at: the EXPENSE-DATE month shows nothing…
    OrgUnitExpenseSummaryResponse byExpenseDatePeriod =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> claimReader.summary(null, expenseDatePeriod));
    assertThat(byExpenseDatePeriod.byCategory()).isEmpty();

    // …the APPROVAL month shows the claim, with the right category/amount/currency.
    OrgUnitExpenseSummaryResponse byApprovedAtPeriod =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> claimReader.summary(null, approvedAtPeriod));
    assertThat(byApprovedAtPeriod.byCategory()).hasSize(1);
    assertThat(byApprovedAtPeriod.byCategory().get(0).categoryName()).isEqualTo("Travel");
    assertThat(byApprovedAtPeriod.byCategory().get(0).totalMinor()).isEqualTo(300_000L);
    assertThat(byApprovedAtPeriod.byCategory().get(0).currency()).isEqualTo("IDR");
    assertThat(byApprovedAtPeriod.approvedReimbursedTotalMinor()).isEqualTo(300_000L);
    assertThat(byApprovedAtPeriod.currency()).isEqualTo("IDR");

    // (contrast) byStatus stays on expense_date — the claim's ONE row shows under the EXPENSE
    // month, not the approval month, proving the two queries are genuinely independent.
    assertThat(byExpenseDatePeriod.byStatus())
        .extracting(OrgUnitExpenseSummaryResponse.StatusCount::status)
        .containsExactly("APPROVED");
    assertThat(byApprovedAtPeriod.byStatus()).isEmpty();

    // (W2) RLS: tenant B, in its own scope, sees NONE of this for the exact same period/scope
    // tenant A just matched — the GROUP BY itself is RLS-scoped, not merely a later findById gate.
    OrgUnitExpenseSummaryResponse forB =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> claimReader.summary(null, approvedAtPeriod));
    assertThat(forB.byCategory()).isEmpty();
    assertThat(forB.byStatus()).isEmpty();
    assertThat(forB.approvedReimbursedTotalMinor()).isZero();
    assertThat(forB.currency()).isNull();
  }
}
