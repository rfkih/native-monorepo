package id.co.nativeapp.finance.bank.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection over a {@code bank_statement_line} row — only the columns the statement-line
 * list/detail endpoints need, never {@code SELECT *} of the entity. Snake-case native-query aliases
 * map to these accessors via Spring Data's projection convention. Reached only from the service +
 * repository layers (ArchUnit projection-layer rule).
 */
public interface StatementLineView {

  UUID getId();

  UUID getBankAccountId();

  LocalDate getLineDate();

  long getAmountMinor();

  String getCurrency();

  String getDescription();

  String getReference();

  String getStatus();

  String getReconciledCategory();

  UUID getJournalEntryId();
}
