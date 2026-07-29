package id.co.nativeapp.finance.tax.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection over a {@code tax_filing} row (Phase 4 Tax / PPN) — only the columns the tax
 * readers need, never {@code SELECT *} of the full entity. Backs the native read queries on {@code
 * TaxFilingRepository} (detail, period-lookup, history). Snake_case native-query aliases map to
 * these accessors via Spring Data's projection-interface convention; lives in the feature's {@code
 * tax.projection} package so the ArchUnit {@code Projection} layer rule enforces it is accessed only
 * from service + repository.
 */
public interface TaxFilingView {

  UUID getId();

  String getPeriod();

  String getTaxType();

  String getStatus();

  String getCurrency();

  long getOutputVatMinor();

  long getInputVatMinor();

  long getNetMinor();

  String getNetDirection();

  UUID getFilingEntryId();

  /** Null until the return is settled. */
  UUID getSettlementEntryId();

  Instant getFiledAt();

  /** Null until the return is settled. */
  Instant getSettledAt();
}
