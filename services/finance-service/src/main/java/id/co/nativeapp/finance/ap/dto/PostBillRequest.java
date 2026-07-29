package id.co.nativeapp.finance.ap.dto;

import jakarta.validation.constraints.Positive;

/**
 * Request body to post a draft bill. {@code termDays} (payment term in days) is optional; when
 * absent the writer applies its default term.
 */
public record PostBillRequest(@Positive Integer termDays) {}
