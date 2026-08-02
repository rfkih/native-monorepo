package id.co.nativeapp.employee.expense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body for {@code POST /api/v1/expense-categories}. {@code glHint} is validated against the
 * seeded whitelist in the domain ({@code null} normalizes to {@code ""}, the general hint).
 */
public record CreateCategoryRequest(
    @NotBlank @Size(max = 128) String name, String glHint, boolean taxable) {}
