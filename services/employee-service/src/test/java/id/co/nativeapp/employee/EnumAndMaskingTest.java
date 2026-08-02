package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.EmployeeStatus;
import id.co.nativeapp.employee.employee.domain.EmploymentType;
import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit coverage of the enum {@code from(...)} parsers (case-insensitive, reject unknown →
 * 400) and the {@link Employee#update} partial-change logic.
 */
class EnumAndMaskingTest {

  @Test
  void ptkpStatusParsesCaseInsensitivelyAndRejectsUnknown() {
    assertThat(PtkpStatus.from("k1")).isEqualTo(PtkpStatus.K1);
    assertThat(PtkpStatus.from("TK0")).isEqualTo(PtkpStatus.TK0);
    assertThatThrownBy(() -> PtkpStatus.from("ZZ9")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PtkpStatus.from(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void employmentTypeParsesCaseInsensitivelyAndRejectsUnknown() {
    assertThat(EmploymentType.from("permanent")).isEqualTo(EmploymentType.PERMANENT);
    assertThat(EmploymentType.from("CONTRACT")).isEqualTo(EmploymentType.CONTRACT);
    assertThatThrownBy(() -> EmploymentType.from("freelance"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void employeeStatusParsesCaseInsensitivelyAndRejectsUnknown() {
    assertThat(EmployeeStatus.from("active")).isEqualTo(EmployeeStatus.ACTIVE);
    assertThat(EmployeeStatus.from("INACTIVE")).isEqualTo(EmployeeStatus.INACTIVE);
    assertThatThrownBy(() -> EmployeeStatus.from("retired"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updatingAnEmployeeReportsWhetherAnyFieldChanged() {
    Employee employee =
        new Employee("Budi", PtkpStatus.TK0, "3201000000000000", "1111222233334444");

    // A no-op update (identical values, all nulls) reports no change.
    assertThat(employee.update(null, null, null, null, null, null, null)).isFalse();
    assertThat(
            employee.update("Budi", PtkpStatus.TK0, null, null, null, EmployeeStatus.ACTIVE, null))
        .isFalse();

    // A real change reports true and applies.
    assertThat(
            employee.update("Budi Santoso", null, null, null, null, EmployeeStatus.INACTIVE, null))
        .isTrue();
    assertThat(employee.getFullName()).isEqualTo("Budi Santoso");
    assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);

    // Changing the PII updates it (still masked everywhere).
    assertThat(
            employee.update(
                null, PtkpStatus.K2, "3209999999999999", "5555666677778888", null, null, null))
        .isTrue();
    assertThat(employee.getPtkpStatus()).isEqualTo(PtkpStatus.K2);
    assertThat(employee.maskedBankAccount()).isEqualTo("****8888");

    // hire_date (Track P Phase P8): starts absent, a real change reports true and applies; a
    // re-apply of the same value is a no-op.
    assertThat(employee.getHireDate()).isNull();
    java.time.LocalDate hireDate = java.time.LocalDate.of(2025, 3, 1);
    assertThat(employee.update(null, null, null, null, null, null, hireDate)).isTrue();
    assertThat(employee.getHireDate()).isEqualTo(hireDate);
    assertThat(employee.update(null, null, null, null, null, null, hireDate)).isFalse();
  }

  @Test
  void aShortBankAccountIsFullyMasked() {
    Employee employee = new Employee("X", PtkpStatus.TK0, "3201000000000000", "12");
    // Too short to reveal a last-4 tail safely.
    assertThat(employee.maskedBankAccount()).isEqualTo("****");
  }

  @Test
  void npwpStartsAbsentAndBecomesSetOnUpdateThenMasksFully() {
    Employee employee =
        new Employee("Budi", PtkpStatus.TK0, "3201000000000000", "1111222233334444");

    // No NPWP on file yet — a new employee, or one HR hasn't recorded it for yet.
    assertThat(employee.hasNpwp()).isFalse();
    assertThat(employee.maskedNpwp()).isNull();

    // Setting it reports a real change and is fully redacted (rule 6), same as the NIK.
    assertThat(employee.update(null, null, null, null, "091234567891000", null, null)).isTrue();
    assertThat(employee.hasNpwp()).isTrue();
    assertThat(employee.maskedNpwp()).isEqualTo("***REDACTED***");

    // Re-applying the same value is a no-op.
    assertThat(employee.update(null, null, null, null, "091234567891000", null, null)).isFalse();
  }
}
