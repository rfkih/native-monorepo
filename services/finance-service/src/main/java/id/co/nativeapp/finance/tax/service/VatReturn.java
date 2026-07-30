package id.co.nativeapp.finance.tax.service;

/**
 * The GL-derived PPN figures for a period (Phase 4 Tax / PPN) — output VAT (credit-net of
 * VAT_OUTPUT / 2200), input VAT (debit-net of VAT_INPUT / 1300), and the period's GL currency
 * ({@code null} when the period has no GL activity). Both amounts are minor units and may be
 * negative in a net-void period (voids exceeded issues); the file path rejects that. Internal to
 * the tax service (the reader's raw computation, before the filing-state overlay).
 */
public record VatReturn(String period, String currency, long outputVatMinor, long inputVatMinor) {}
