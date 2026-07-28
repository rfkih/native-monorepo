package id.co.nativeapp.finance.ar.dto;

import jakarta.validation.constraints.Positive;

/**
 * Request body to issue a draft invoice. {@code termDays} (payment term in days) is optional; when
 * absent the writer applies its default term.
 */
public record IssueInvoiceRequest(@Positive Integer termDays) {}
