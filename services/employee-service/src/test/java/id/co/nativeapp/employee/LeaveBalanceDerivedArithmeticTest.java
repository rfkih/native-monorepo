package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.dto.LeaveBalanceResponse;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceReader;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceWriter;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The derived leave-balance ARITHMETIC (ADR 0033 §4): {@code remaining = granted + adjustment -
 * used}, {@code used} always re-derived from APPROVED ANNUAL requests, and a request's YEAR is
 * bucketed by its {@code start_date} — a request starting in a DIFFERENT year never counts against
 * this year's balance, even when the two years' requests are approved back to back.
 */
@SpringBootTest
class LeaveBalanceDerivedArithmeticTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String CLAIMANT_ACTOR = "aaaaaaaa-1111-1111-1111-111111111111";
  private static final String MANAGER_ACTOR = "manager-not-linked";

  @Autowired private EmployeeService employeeService;
  @Autowired private LeaveRequestService leaveRequestService;
  @Autowired private LeaveBalanceReader leaveBalanceReader;
  @Autowired private LeaveBalanceWriter leaveBalanceWriter;

  private UUID employeeId;

  private UUID createLinkedEmployee() throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        CLAIMANT_ACTOR,
        () -> {
          UUID id =
              employeeService
                  .create(
                      new CreateEmployeeCommand(
                          "Budi", "TK0", "3201234567890123", "1234567890123456"))
                  .getId();
          employeeService.linkUser(id, CLAIMANT_ACTOR, null);
          return id;
        });
  }

  @Test
  void aFreshEmployeeWithNoRowsDefaultsToTheStatutoryGrant() throws Exception {
    employeeId = createLinkedEmployee();

    LeaveBalanceResponse balance =
        TenantContext.callAs(TENANT_A, CLAIMANT_ACTOR, () -> leaveBalanceReader.myBalance(2026));

    assertThat(balance.grantedDays()).isEqualTo(12);
    assertThat(balance.adjustmentDays()).isEqualTo(0);
    assertThat(balance.usedDays()).isEqualTo(0);
    assertThat(balance.remaining()).isEqualTo(12);
  }

  @Test
  void anAdjustmentShiftsTheRemainingWithoutTouchingUsage() throws Exception {
    employeeId = createLinkedEmployee();

    TenantContext.runAs(
        TENANT_A, MANAGER_ACTOR, () -> leaveBalanceWriter.adjust(employeeId, 2026, -3));

    LeaveBalanceResponse balance =
        TenantContext.callAs(TENANT_A, CLAIMANT_ACTOR, () -> leaveBalanceReader.myBalance(2026));

    assertThat(balance.grantedDays()).isEqualTo(12);
    assertThat(balance.adjustmentDays()).isEqualTo(-3);
    assertThat(balance.usedDays()).isEqualTo(0);
    assertThat(balance.remaining()).isEqualTo(9);
  }

  @Test
  void usedDaysSumsOnlyApprovedAnnualRequestsInTheYear() throws Exception {
    employeeId = createLinkedEmployee();

    UUID annualApproved =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () ->
                leaveRequestService
                    .create(
                        LeaveType.ANNUAL,
                        LocalDate.of(2026, 3, 2),
                        LocalDate.of(2026, 3, 5),
                        4,
                        "arith-1")
                    .getId());
    UUID annualStillSubmitted =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () ->
                leaveRequestService
                    .create(
                        LeaveType.ANNUAL,
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 5, 2),
                        2,
                        "arith-2")
                    .getId());
    UUID sickApproved =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () ->
                leaveRequestService
                    .create(
                        LeaveType.SICK,
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 4, 1),
                        1,
                        "arith-3")
                    .getId());

    TenantContext.runAs(
        TENANT_A,
        MANAGER_ACTOR,
        () -> {
          leaveRequestService.approve(annualApproved, null, "arith-1-approve");
          // SICK is approved too, but must NOT count toward the ANNUAL balance.
          leaveRequestService.approve(sickApproved, null, "arith-3-approve");
        });

    LeaveBalanceResponse balance =
        TenantContext.callAs(TENANT_A, CLAIMANT_ACTOR, () -> leaveBalanceReader.myBalance(2026));

    // Only the 4-day APPROVED ANNUAL request counts — the still-SUBMITTED ANNUAL request and the
    // APPROVED SICK request are both excluded.
    assertThat(balance.usedDays()).isEqualTo(4);
    assertThat(balance.remaining()).isEqualTo(8);
    assertThat(annualStillSubmitted).isNotNull();
  }

  @Test
  void aRequestStartingInADifferentYearDoesNotCountAgainstThisYearsBalance() throws Exception {
    employeeId = createLinkedEmployee();

    UUID inYear2025 =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () ->
                leaveRequestService
                    .create(
                        LeaveType.ANNUAL,
                        LocalDate.of(2025, 12, 29),
                        LocalDate.of(2025, 12, 31),
                        3,
                        "arith-y1")
                    .getId());
    UUID inYear2026 =
        TenantContext.callAs(
            TENANT_A,
            CLAIMANT_ACTOR,
            () ->
                leaveRequestService
                    .create(
                        LeaveType.ANNUAL,
                        LocalDate.of(2026, 1, 5),
                        LocalDate.of(2026, 1, 7),
                        3,
                        "arith-y2")
                    .getId());

    TenantContext.runAs(
        TENANT_A,
        MANAGER_ACTOR,
        () -> {
          leaveRequestService.approve(inYear2025, null, "arith-y1-approve");
          leaveRequestService.approve(inYear2026, null, "arith-y2-approve");
        });

    LeaveBalanceResponse balance2025 =
        TenantContext.callAs(TENANT_A, CLAIMANT_ACTOR, () -> leaveBalanceReader.myBalance(2025));
    LeaveBalanceResponse balance2026 =
        TenantContext.callAs(TENANT_A, CLAIMANT_ACTOR, () -> leaveBalanceReader.myBalance(2026));

    assertThat(balance2025.usedDays()).isEqualTo(3);
    assertThat(balance2026.usedDays()).isEqualTo(3);
  }
}
