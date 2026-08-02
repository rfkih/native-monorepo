package id.co.nativeapp.employee.expense.dto;

import jakarta.validation.constraints.NotBlank;

/** The body for {@code POST /api/v1/expense-claims/{id}/refuse} — the comment is REQUIRED. */
public record RefuseClaimRequest(@NotBlank String comment) {}
