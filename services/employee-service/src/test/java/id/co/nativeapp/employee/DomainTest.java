package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.assignment.domain.Assignment;
import id.co.nativeapp.employee.assignment.dto.AssignmentResponse;
import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.employee.employee.dto.EmployeeResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit domain logic proofs — no Spring context: the concurrency overlap that drives the
 * same-legal-employer check, the effective-dating validation, and that a response DTO masks PII
 * (rule 6) and never carries the raw NIK / bank account.
 */
class DomainTest {

  private static final UUID EMP = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID ORG = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void overlappingEffectivePeriodsAreConcurrent() {
    Assignment a =
        new Assignment(
            EMP, ORG, null, "cashier", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    // Another period that overlaps [2026-01-01, 2026-12-31].
    assertThat(a.overlaps(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))).isTrue();
  }

  @Test
  void nonOverlappingEffectivePeriodsAreNotConcurrent() {
    Assignment a =
        new Assignment(
            EMP, ORG, null, "cashier", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
    // A period entirely after this assignment's end.
    assertThat(a.overlaps(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))).isFalse();
  }

  @Test
  void anEffectiveToBeforeFromIsRejected() {
    assertThatThrownBy(
            () ->
                new Assignment(
                    EMP, ORG, null, "cashier", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anOpenEndedAssignmentUsesTheFarFutureSentinel() {
    Assignment a = new Assignment(EMP, ORG, null, "cashier", LocalDate.of(2026, 6, 1), null);
    assertThat(a.getEffectiveTo()).isEqualTo(Assignment.OPEN_ENDED);
  }

  @Test
  void theEmployeeResponseMasksPiiAndNeverCarriesTheRawValues() {
    Employee employee =
        new Employee("Budi Santoso", PtkpStatus.K1, "3201234567890123", "1234567890123456");
    EmployeeResponse response = EmployeeResponse.from(employee);

    // The NIK is fully redacted; the bank account shows only its last 4 digits.
    assertThat(response.maskedNik()).isEqualTo("***REDACTED***");
    assertThat(response.maskedBankAccount()).isEqualTo("****3456");
    // The raw PII never appears anywhere in the response.
    assertThat(response.toString())
        .doesNotContain("3201234567890123")
        .doesNotContain("1234567890123456");
  }

  @Test
  void theEmployeeToStringNeverLeaksPii() {
    Employee employee =
        new Employee("Budi Santoso", PtkpStatus.K1, "3201234567890123", "1234567890123456");
    assertThat(employee.toString())
        .doesNotContain("3201234567890123")
        .doesNotContain("1234567890123456")
        .doesNotContain("Budi Santoso");
  }

  @Test
  void theAssignmentResponseExposesTheDimensionsWithoutPii() {
    Assignment a =
        new Assignment(
            EMP, ORG, EMP, "shift_lead", LocalDate.of(2026, 6, 1), LocalDate.of(9999, 12, 31));
    AssignmentResponse response = AssignmentResponse.from(a);
    assertThat(response.orgUnitId()).isEqualTo(ORG);
    assertThat(response.reportingTo()).isEqualTo(EMP);
    assertThat(response.role()).isEqualTo("shift_lead");
  }
}
