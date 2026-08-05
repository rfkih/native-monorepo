package id.co.nativeapp.finance.bank.projection;

import java.util.UUID;

/**
 * Read projection over a {@code bank_account} row — only the columns the bank-account list/detail
 * endpoints need, never {@code SELECT *} of the entity. Snake-case native-query aliases map to
 * these accessors via Spring Data's projection convention. Reached only from the service +
 * repository layers (ArchUnit projection-layer rule).
 */
public interface BankAccountView {

  UUID getId();

  String getName();

  String getBankName();

  String getAccountNumber();

  String getCurrency();

  boolean getActive();
}
