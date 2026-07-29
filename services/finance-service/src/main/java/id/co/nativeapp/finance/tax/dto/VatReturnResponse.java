package id.co.nativeapp.finance.tax.dto;

import java.util.UUID;

/**
 * The PPN (VAT) return for a period (Phase 4 Tax / PPN) — the GL-derived report. {@code
 * outputVatMinor} is the credit-net of VAT_OUTPUT (2200), {@code inputVatMinor} the debit-net of
 * VAT_INPUT (1300), both over the period; {@code netMinor} is the magnitude {@code |output − input|}
 * and {@code netDirection} its sign ({@code PAYABLE} / {@code CREDITABLE}).
 *
 * <p>{@code currency} is null for a period with no GL activity. {@code filed} + {@code filingId} +
 * {@code filingStatus} reflect whether the period has already been filed (so the UI can disable the
 * File action and offer Settle). All amounts are minor units — never a float.
 */
public record VatReturnResponse(
    String period,
    String currency,
    long outputVatMinor,
    long inputVatMinor,
    long netMinor,
    String netDirection,
    boolean filed,
    UUID filingId,
    String filingStatus) {}
