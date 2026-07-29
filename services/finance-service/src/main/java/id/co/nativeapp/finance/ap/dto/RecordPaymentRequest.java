package id.co.nativeapp.finance.ap.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body to record a payment against a bill. {@code amountMinor} is in the bill currency's
 * minor units (must be positive and not exceed the outstanding balance); {@code method} is an
 * optional free-text tender label (defaults to {@code CASH}).
 */
public record RecordPaymentRequest(@Positive long amountMinor, @Size(max = 32) String method) {}
