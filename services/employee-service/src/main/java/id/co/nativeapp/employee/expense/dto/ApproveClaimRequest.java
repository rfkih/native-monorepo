package id.co.nativeapp.employee.expense.dto;

/**
 * The body for {@code POST /api/v1/expense-claims/{id}/approve} — the comment is OPTIONAL, unlike a
 * refusal.
 */
public record ApproveClaimRequest(String comment) {}
