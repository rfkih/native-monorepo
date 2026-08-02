package id.co.nativeapp.employee.expense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The body for {@code POST /api/v1/me/expense-claims} (create) and {@code PUT
 * /api/v1/me/expense-claims/{id}} (a DRAFT-only full replace) — the caller is always resolved from
 * the bound tenant/actor, never from this body (rule 5).
 *
 * @param reimbursementMethod {@code "PAYROLL"} or {@code "DIRECT"}; {@code null} defaults to
 *     PAYROLL
 */
public record CreateClaimRequest(
    @NotNull UUID categoryId,
    @Positive long amountMinor,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotNull LocalDate expenseDate,
    @Size(max = 160) String merchant,
    @Size(max = 4000) String note,
    @Pattern(regexp = "PAYROLL|DIRECT") String reimbursementMethod) {}
