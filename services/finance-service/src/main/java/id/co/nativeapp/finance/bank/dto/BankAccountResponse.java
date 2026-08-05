package id.co.nativeapp.finance.bank.dto;

import java.util.UUID;

/** API response for a bank account. */
public record BankAccountResponse(
    UUID id, String name, String bankName, String accountNumber, String currency, boolean active) {}
