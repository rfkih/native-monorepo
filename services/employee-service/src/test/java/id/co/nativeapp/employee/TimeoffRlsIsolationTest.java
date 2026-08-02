package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.timeoff.domain.DayKind;
import id.co.nativeapp.employee.timeoff.domain.LeaveRequestNotFoundException;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntryNotFoundException;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceReader;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceWriter;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestReader;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestService;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryReader;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryService;
import id.co.nativeapp.employee.timeoff.service.WorkCalendarReader;
import id.co.nativeapp.employee.timeoff.service.WorkCalendarWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cross-tenant isolation of every P6 attendance/time-off table (V12: {@code leave_request}, {@code
 * overtime_entry}, {@code leave_balance}, {@code work_calendar}, {@code timeoff_request_event}) via
 * the auto-applied RLS (rule 5). Tenant A creates one row of each (through a full submit+decide
 * cycle so a {@code timeoff_request_event} audit row genuinely exists for both kinds — the
 * expense-claims RLS test's data-engineer-review finding, mirrored here); tenant B, in its own
 * scope, sees NONE of it. No manual {@code WHERE company_id} anywhere — purely RLS (Postgres
 * Testcontainers, non-superuser app role).
 */
@SpringBootTest
class TimeoffRlsIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR_A = "aaaaaaaa-1111-1111-1111-111111111111";
  private static final String ACTOR_B = "bbbbbbbb-2222-2222-2222-222222222222";
  private static final String MANAGER_ACTOR = "manager-not-linked";

  @Autowired private EmployeeService employeeService;
  @Autowired private LeaveRequestService leaveRequestService;
  @Autowired private LeaveRequestReader leaveRequestReader;
  @Autowired private OvertimeEntryService overtimeEntryService;
  @Autowired private OvertimeEntryReader overtimeEntryReader;
  @Autowired private LeaveBalanceReader leaveBalanceReader;
  @Autowired private LeaveBalanceWriter leaveBalanceWriter;
  @Autowired private WorkCalendarReader workCalendarReader;
  @Autowired private WorkCalendarWriter workCalendarWriter;

  @Test
  void tenantBCannotSeeTenantAsAttendanceRows() throws Exception {
    record Ids(UUID employeeId, UUID leaveRequestId, UUID overtimeEntryId) {}

    Ids ids =
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

              UUID leaveRequestId =
                  leaveRequestService
                      .create(
                          LeaveType.ANNUAL,
                          LocalDate.of(2026, 8, 10),
                          LocalDate.of(2026, 8, 11),
                          2,
                          "leave-idem-a")
                      .getId();

              UUID overtimeEntryId =
                  overtimeEntryService
                      .create(LocalDate.of(2026, 8, 10), 90, DayKind.WEEKDAY, "ot-idem-a")
                      .getId();

              leaveBalanceWriter.adjust(employeeId, 2026, 2);
              workCalendarWriter.upsert(6, 25);

              return new Ids(employeeId, leaveRequestId, overtimeEntryId);
            });

    // Manager decides both (a SECOND action -> a SECOND timeoff_request_event row per kind, both
    // scoped to tenant A).
    TenantContext.runAs(
        TENANT_A,
        MANAGER_ACTOR,
        () -> {
          leaveRequestService.approve(ids.leaveRequestId(), "ok", "leave-approve-a");
          overtimeEntryService.approve(ids.overtimeEntryId(), "ok", "ot-approve-a");
        });

    // A sees its own rows.
    assertThat(
            TenantContext.callAs(
                TENANT_A, ACTOR_A, () -> leaveRequestReader.one(ids.leaveRequestId())))
        .isNotNull();
    assertThat(
            TenantContext.callAs(
                TENANT_A, ACTOR_A, () -> overtimeEntryReader.one(ids.overtimeEntryId())))
        .isNotNull();
    assertThat(
            TenantContext.callAs(TENANT_A, ACTOR_A, () -> leaveBalanceReader.myBalance(2026))
                .adjustmentDays())
        .isEqualTo(2);
    assertThat(TenantContext.callAs(TENANT_A, ACTOR_A, workCalendarReader::get).daysPerWeek())
        .isEqualTo(6);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM timeoff_request_event WHERE request_kind = 'LEAVE' AND"
                    + " request_id = ?",
                ids.leaveRequestId()))
        .isEqualTo(2L); // SUBMIT + APPROVE
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM timeoff_request_event WHERE request_kind = 'OVERTIME' AND"
                    + " request_id = ?",
                ids.overtimeEntryId()))
        .isEqualTo(2L);

    // B, in its own scope, sees NONE of it (RLS fail-closed).
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B, ACTOR_B, () -> leaveRequestReader.one(ids.leaveRequestId())))
        .isInstanceOf(LeaveRequestNotFoundException.class);
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B, ACTOR_B, () -> overtimeEntryReader.one(ids.overtimeEntryId())))
        .isInstanceOf(OvertimeEntryNotFoundException.class);
    // A brand-new tenant B has no employee link yet, so its OWN balance/calendar reads would 404
    // for a different reason (EmployeeNotLinkedException) — the row-level probe below is the
    // precise RLS assertion instead.
    assertThat(
            countAsTenant(
                TENANT_B, "SELECT count(*) FROM leave_request WHERE id = ?", ids.leaveRequestId()))
        .isZero();
    assertThat(
            countAsTenant(
                TENANT_B,
                "SELECT count(*) FROM overtime_entry WHERE id = ?",
                ids.overtimeEntryId()))
        .isZero();
    assertThat(
            countAsTenant(
                TENANT_B,
                "SELECT count(*) FROM leave_balance WHERE employee_id = ?",
                ids.employeeId()))
        .isZero();
    assertThat(countAsTenant(TENANT_B, "SELECT count(*) FROM work_calendar")).isZero();
    assertThat(
            countAsTenant(
                TENANT_B,
                "SELECT count(*) FROM timeoff_request_event WHERE request_kind = 'LEAVE' AND"
                    + " request_id = ?",
                ids.leaveRequestId()))
        .isZero();
    assertThat(
            countAsTenant(
                TENANT_B,
                "SELECT count(*) FROM timeoff_request_event WHERE request_kind = 'OVERTIME' AND"
                    + " request_id = ?",
                ids.overtimeEntryId()))
        .isZero();
  }
}
