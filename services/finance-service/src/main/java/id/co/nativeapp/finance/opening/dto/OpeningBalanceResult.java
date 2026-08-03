package id.co.nativeapp.finance.opening.dto;

import id.co.nativeapp.finance.opening.domain.CompanyOpeningBalance;

/**
 * Outcome of a record-opening-balances attempt: the (possibly replayed) marker row and whether THIS
 * request created it — the controller's 201-vs-200 replay signal (ENGINEERING-STANDARDS §1.1).
 */
public record OpeningBalanceResult(CompanyOpeningBalance openingBalance, boolean created) {}
