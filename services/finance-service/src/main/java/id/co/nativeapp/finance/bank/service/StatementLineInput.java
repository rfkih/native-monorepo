package id.co.nativeapp.finance.bank.service;

import java.time.LocalDate;

/**
 * A service-layer input for one imported statement line: a date, a SIGNED amount in minor units
 * (positive = deposit, negative = withdrawal), and an optional description/reference. The currency
 * is NOT part of this input — {@code StatementLineWriter} stamps every imported line with the
 * owning bank account's own (immutable) currency; finance never trusts a client-supplied currency
 * for a statement line.
 *
 * @param lineDate the statement line's date
 * @param amountMinor the SIGNED amount in the bank account currency's minor units (non-zero)
 * @param description an optional free-text description
 * @param reference an optional free-text reference (e.g. a bank transaction id)
 */
public record StatementLineInput(
    LocalDate lineDate, long amountMinor, String description, String reference) {}
