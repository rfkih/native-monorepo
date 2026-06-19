package id.co.nativeapp.finance.gl.domain;

import id.co.nativeapp.finance.mapping.domain.AccountType;
import java.util.Objects;

/**
 * The double-entry NORMAL BALANCE of a {@link AccountType} — derived, never stored (no new chart
 * column). A debit-normal account increases with debits and decreases with credits; a credit-normal
 * account is the reverse.
 *
 * <p><strong>Sign convention (mirrors {@code TrialBalanceReader.applySign}):</strong>
 *
 * <ul>
 *   <li>Debit-normal: {@link AccountType#ASSET}, {@link AccountType#EXPENSE}.
 *   <li>Credit-normal: {@link AccountType#LIABILITY}, {@link AccountType#EQUITY}, {@link
 *       AccountType#REVENUE}.
 * </ul>
 */
public enum NormalBalance {
  DEBIT,
  CREDIT;

  /**
   * Derives the normal balance for an account type. Consistent with the sign convention used in
   * {@code TrialBalanceReader} and {@code GroupCloseWriter}.
   *
   * @param accountType the account classification
   * @return {@link #DEBIT} for ASSET/EXPENSE; {@link #CREDIT} for LIABILITY/EQUITY/REVENUE
   * @throws NullPointerException if {@code accountType} is null
   */
  public static NormalBalance of(AccountType accountType) {
    Objects.requireNonNull(accountType, "accountType");
    return switch (accountType) {
      case ASSET, EXPENSE -> DEBIT;
      case LIABILITY, EQUITY, REVENUE -> CREDIT;
    };
  }
}
