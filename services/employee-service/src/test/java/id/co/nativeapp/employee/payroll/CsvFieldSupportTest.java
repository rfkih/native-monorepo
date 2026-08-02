package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.service.CsvFieldSupport;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link CsvFieldSupport} (the ADR 0032 §S2/S3 formula-injection lesson, closed
 * in Track P phase P9): every field beginning with {@code =}, {@code +}, {@code -}, or {@code @}
 * gets a leading single-quote guard BEFORE RFC-4180 quoting; ordinary fields are untouched or
 * quoted per the usual comma/quote/newline rule; {@code null} becomes an empty cell.
 */
class CsvFieldSupportTest {

  @Test
  void aFieldStartingWithEqualsGetsAFormulaInjectionGuard() {
    assertThat(CsvFieldSupport.field("=SUM(A1:A9)")).isEqualTo("'=SUM(A1:A9)");
  }

  @Test
  void aFieldStartingWithPlusMinusOrAtGetsTheSameGuard() {
    assertThat(CsvFieldSupport.field("+1234")).isEqualTo("'+1234");
    assertThat(CsvFieldSupport.field("-1234")).isEqualTo("'-1234");
    assertThat(CsvFieldSupport.field("@cmd")).isEqualTo("'@cmd");
  }

  @Test
  void theGuardAppliesBeforeRfc4180QuotingWhenTheFieldAlsoContainsAComma() {
    // "=cmd,evil" -> guarded to "'=cmd,evil" -> then quoted because it now (still) contains a
    // comma.
    assertThat(CsvFieldSupport.field("=cmd,evil")).isEqualTo("\"'=cmd,evil\"");
  }

  @Test
  void anOrdinaryFieldIsReturnedUnchanged() {
    assertThat(CsvFieldSupport.field("Budi Santoso")).isEqualTo("Budi Santoso");
    assertThat(CsvFieldSupport.field("3206000000000001")).isEqualTo("3206000000000001");
  }

  @Test
  void aFieldContainingACommaOrQuoteOrNewlineIsRfc4180Quoted() {
    assertThat(CsvFieldSupport.field("Budi, Jr.")).isEqualTo("\"Budi, Jr.\"");
    assertThat(CsvFieldSupport.field("Budi \"The Chef\"")).isEqualTo("\"Budi \"\"The Chef\"\"\"");
    assertThat(CsvFieldSupport.field("Budi\nSantoso")).isEqualTo("\"Budi\nSantoso\"");
  }

  @Test
  void nullBecomesAnEmptyCell() {
    assertThat(CsvFieldSupport.field(null)).isEmpty();
  }

  @Test
  void anEmptyStringIsUnaffectedByTheGuard() {
    assertThat(CsvFieldSupport.field("")).isEmpty();
  }
}
