package id.co.nativeapp.employee.expense.dto;

import jakarta.validation.constraints.Size;

/**
 * The body for {@code PATCH /api/v1/expense-categories/{id}} — a partial update; a {@code null}
 * field leaves it unchanged.
 */
public record UpdateCategoryRequest(
    @Size(max = 128) String name, String glHint, Boolean taxable, Boolean active) {}
