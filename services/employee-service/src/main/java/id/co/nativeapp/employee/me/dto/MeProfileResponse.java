package id.co.nativeapp.employee.me.dto;

import id.co.nativeapp.employee.assignment.dto.AssignmentResponse;
import id.co.nativeapp.employee.employee.dto.ContractResponse;
import java.util.List;
import java.util.UUID;

/**
 * The caller's own profile ({@code GET /api/v1/me/profile}). PII stays MASKED even to the person
 * themselves (rule 6 — the console never needs to display a full NIK/bank account; corrections go
 * through HR): only payslip AMOUNTS are ever decrypted on the /me surface.
 *
 * @param employeeId the caller's employee id
 * @param fullName the caller's display name
 * @param ptkpStatus the PTKP tax status code
 * @param status ACTIVE | INACTIVE
 * @param maskedNik the NIK, fully redacted
 * @param maskedBankAccount the bank account, masked to its last 4 digits
 * @param hasNpwp whether an NPWP is on file
 * @param maskedNpwp the NPWP, fully redacted, or null when none is on file
 * @param assignments the caller's assignments (current and past)
 * @param contracts the caller's employment contracts
 */
public record MeProfileResponse(
    UUID employeeId,
    String fullName,
    String ptkpStatus,
    String status,
    String maskedNik,
    String maskedBankAccount,
    boolean hasNpwp,
    String maskedNpwp,
    List<AssignmentResponse> assignments,
    List<ContractResponse> contracts) {}
