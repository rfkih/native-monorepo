package id.co.nativeapp.employee.payroll.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A compensation package as the console sees it — <strong>always masked</strong>. Base pay is
 * salary PII (rule 6): every read carries {@code amountMasked = "***"} and no amount field exists
 * on this DTO at all; the list read path never even selects the ciphertext column.
 *
 * @param id the package id
 * @param employmentContractId the contract the package belongs to
 * @param payFrequency MONTHLY (the only frequency today)
 * @param effectiveFrom the package's start
 * @param effectiveTo the package's end ({@code 9999-12-31} = open)
 * @param amountMasked always {@code "***"}
 */
public record CompensationResponse(
    UUID id,
    UUID employmentContractId,
    String payFrequency,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String amountMasked) {

  public static final String MASK = "***";
}
