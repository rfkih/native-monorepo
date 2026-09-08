package id.co.nativeapp.finance.platform.dto;

/**
 * One tender family and the payer the merchant named for it (ADR 0076) — "QRIS is settled by
 * Shopee". A family with no configuration is simply absent from the list.
 */
public record SettlementSourceResponse(String sourceKind, String sourceCode) {}
