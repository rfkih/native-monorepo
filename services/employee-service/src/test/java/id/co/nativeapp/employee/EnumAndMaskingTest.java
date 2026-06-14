package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.employee.Employee;
import id.co.nativeapp.employee.employee.EmployeeStatus;
import id.co.nativeapp.employee.employee.EmploymentType;
import id.co.nativeapp.employee.employee.PtkpStatus;
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
    assertThat(employee.update(null, null, null, null, null)).isFalse();
    assertThat(employee.update("Budi", PtkpStatus.TK0, null, null, EmployeeStatus.ACTIVE))
        .isFalse();

    // A real change reports true and applies.
    assertThat(employee.update("Budi Santoso", null, null, null, EmployeeStatus.INACTIVE)).isTrue();
    assertThat(employee.getFullName()).isEqualTo("Budi Santoso");
    assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);

    // Changing the PII updates it (still masked everywhere).
    assertThat(employee.update(null, PtkpStatus.K2, "3209999999999999", "5555666677778888", null))
        .isTrue();
    assertThat(employee.getPtkpStatus()).isEqualTo(PtkpStatus.K2);
    assertThat(employee.maskedBankAccount()).isEqualTo("****8888");
  }

  @Test
  void aShortBankAccountIsFullyMasked() {
    Employee employee = new Employee("X", PtkpStatus.TK0, "3201000000000000", "12");
    // Too short to reveal a last-4 tail safely.
    assertThat(employee.maskedBankAccount()).isEqualTo("****");
  }
}
