package id.co.nativeapp.employee.payroll.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for an employee's compensation packages — deliberately WITHOUT the {@code
 * base_pay_enc} column: the list read never selects (let alone decrypts) the salary ciphertext
 * (rule 6); the console only sees masked rows. Snake_case native-query aliases map to these
 * accessors (CODE-STRUCTURE §3.3).
 */
public interface CompensationView {

  UUID getId();

  UUID getEmploymentContractId();

  String getPayFrequency();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();
}
