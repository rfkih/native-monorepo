package id.co.nativeapp.finance.grouptb.messaging;

import java.util.List;
import java.util.UUID;

/**
 * The decoded {@code TrialBalancePublished} event (P3d SEAM 2) — already parsed out of the raw Avro
 * {@link org.apache.avro.generic.GenericRecord} into exactly the fields the group ingest needs.
 *
 * <p><strong>{@code companyId} is the MEMBER company</strong> whose trial balance this is — a
 * dimension on the ingested {@code group_trial_balance} lines, NOT the tenant the write is bound
 * to. The write is bound to the group's LEAD company (resolved from the local group read model) +
 * {@code groupId}, the two-GUC conjunction scope.
 *
 * @param eventId the source event UUID (idempotency key)
 * @param companyId the MEMBER company the figures came from (a dimension, not the tenant)
 * @param groupId the consolidation group the member belongs to (the second RLS dimension)
 * @param period the accounting period {@code YYYY-MM}
 * @param baseCurrency the member's base ISO-4217 currency
 * @param reconciled whether the member's trial balance reconciled before publish
 * @param usesIllustrativeRules whether the figures are illustrative-placeholder-derived
 * @param lines the member's trial-balance lines for the period
 */
public record TrialBalancePublishedEvent(
    UUID eventId,
    UUID companyId,
    UUID groupId,
    String period,
    String baseCurrency,
    boolean reconciled,
    boolean usesIllustrativeRules,
    List<TrialBalanceLine> lines) {

  /**
   * One decoded trial-balance line.
   *
   * @param glAccountCode the resolved chart_of_account account code
   * @param accountType the GL account class (ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE)
   * @param postingType the posting kind, carried verbatim
   * @param amountMinor the line amount in minor units (integer; never a float)
   * @param currency the line's ISO-4217 currency code
   * @param relatedPartyCounterpartyId the intercompany counterparty, or {@code null}
   * @param intercompanyRef a free-form intercompany reference, or {@code null}
   */
  public record TrialBalanceLine(
      String glAccountCode,
      String accountType,
      String postingType,
      long amountMinor,
      String currency,
      UUID relatedPartyCounterpartyId,
      String intercompanyRef) {}
}
