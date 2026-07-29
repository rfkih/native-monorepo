package id.co.nativeapp.finance.bank.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * API response for a bank statement line. {@code amountMinor} is SIGNED (positive = deposit,
 * negative = withdrawal). {@code reconciledCategory} / {@code journalEntryId} are {@code null}
 * while {@code status} is {@code UNRECONCILED}.
 */
public record StatementLineResponse(
    UUID id,
    UUID bankAccountId,
    LocalDate lineDate,
    long amountMinor,
    String currency,
    String description,
    String reference,
    String status,
    String reconciledCategory,
    UUID journalEntryId) {}
