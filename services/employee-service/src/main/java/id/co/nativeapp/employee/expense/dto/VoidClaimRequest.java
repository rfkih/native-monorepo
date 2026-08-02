package id.co.nativeapp.employee.expense.dto;

import jakarta.validation.constraints.NotBlank;

/** The body for {@code POST /api/v1/expense-claims/{id}/void} — the comment is REQUIRED. */
public record VoidClaimRequest(@NotBlank String comment) {}
