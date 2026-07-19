package id.co.nativeapp.finance.withinclose.dto;

/**
 * List-item DTO for {@code GET /api/v1/closes} — a single historical close record for the bound
 * company. Mapped from the native-query projection in the service layer — never directly from an
 * entity (DTO-at-the-boundary, CODE-STRUCTURE §3.3).
 *
 * @param closeId the {@code within_company_close} row id (string UUID)
 * @param period the accounting period {@code YYYY-MM} that was closed
 * @param baseCurrency the ISO-4217 base currency code of the close
 * @param firstClose always {@code true} for historical records (only real first-close rows exist;
 *     the idempotent re-close path does not persist a second row)
 * @param reconciled whether the trial balance reconciled (always {@code true} in practice)
 * @param usesIllustrativeRules whether any posting in the period was illustrative-placeholder-derived
 */
public record CloseHistoryResponse(
    String closeId,
    String period,
    String baseCurrency,
    boolean firstClose,
    boolean reconciled,
    boolean usesIllustrativeRules) {}
