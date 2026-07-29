package id.co.nativeapp.finance.tax.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A filed PPN return (Phase 4 Tax / PPN) — the full seal, used for both the filing detail and each
 * history row. All amounts are minor units; {@code netMinor} is the magnitude and {@code
 * netDirection} the sign. {@code settlementEntryId} / {@code settledAt} are null until the return is
 * settled.
 */
public record TaxFilingResponse(
    UUID id,
    String period,
    String taxType,
    String status,
    String currency,
    long outputVatMinor,
    long inputVatMinor,
    long netMinor,
    String netDirection,
    UUID filingEntryId,
    UUID settlementEntryId,
    Instant filedAt,
    Instant settledAt) {}
