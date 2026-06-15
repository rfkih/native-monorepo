package id.co.nativeapp.finance;

import id.co.nativeapp.finance.group.domain.GroupMember;
import id.co.nativeapp.finance.group.messaging.GroupDefinedEvent;
import id.co.nativeapp.finance.group.messaging.GroupMembershipChangedEvent;
import id.co.nativeapp.finance.group.service.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent.TrialBalanceLine;
import id.co.nativeapp.finance.grouptb.service.GroupTrialBalanceIngestService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Shared seeding helpers for the P3d SEAM 3a group-close tests — registers a group (GroupDefined),
 * adds members (GroupMembershipChanged), and ingests member trial balances (TrialBalancePublished)
 * through the REAL consumer services, so every row lands under the correct lead tenant + group
 * scope via the production code paths.
 */
final class GroupCloseFixtures {

  private final GroupReadModelService groupReadModel;
  private final GroupTrialBalanceIngestService ingestService;

  GroupCloseFixtures(
      GroupReadModelService groupReadModel, GroupTrialBalanceIngestService ingestService) {
    this.groupReadModel = groupReadModel;
    this.ingestService = ingestService;
  }

  /** Registers a group led by {@code lead} with the given reporting currency. */
  void defineGroup(UUID groupId, UUID lead, String reportingCurrency) {
    groupReadModel.handleGroupDefined(
        new GroupDefinedEvent(UUID.randomUUID(), groupId, lead, reportingCurrency, "Test Group"));
  }

  /** Adds an OPEN-ended member from {@code effectiveFrom} (active through the sentinel). */
  void addMember(UUID groupId, UUID member, LocalDate effectiveFrom) {
    addMember(groupId, member, effectiveFrom, GroupMember.OPEN_ENDED);
  }

  /** Adds a member with an explicit effective window. */
  void addMember(UUID groupId, UUID member, LocalDate effectiveFrom, LocalDate effectiveTo) {
    groupReadModel.handleMembershipChanged(
        new GroupMembershipChangedEvent(
            UUID.randomUUID(), groupId, member, "ADDED", effectiveFrom, effectiveTo));
  }

  /** Ingests a member trial balance for the period (one or more lines), published reconciled. */
  void ingestTrialBalance(
      UUID groupId, UUID member, String period, String currency, List<TrialBalanceLine> lines) {
    ingestService.handle(
        new TrialBalancePublishedEvent(
            UUID.randomUUID(), member, groupId, period, currency, true, false, lines));
  }

  /**
   * Ingests a member trial balance, publishing it with an explicit {@code reconciled} flag — used
   * to drive the SEAM-3b native-balance gate (a {@code reconciled = false} member HOLDs the close).
   */
  void ingestTrialBalance(
      UUID groupId,
      UUID member,
      String period,
      String currency,
      boolean reconciled,
      List<TrialBalanceLine> lines) {
    ingestService.handle(
        new TrialBalancePublishedEvent(
            UUID.randomUUID(), member, groupId, period, currency, reconciled, false, lines));
  }

  /** Ingests a member trial balance, marking it illustrative-placeholder-derived. */
  void ingestIllustrativeTrialBalance(
      UUID groupId, UUID member, String period, String currency, List<TrialBalanceLine> lines) {
    ingestService.handle(
        new TrialBalancePublishedEvent(
            UUID.randomUUID(), member, groupId, period, currency, true, true, lines));
  }

  // ----------------------------------------------------------------------- line builders

  static TrialBalanceLine revenue(long amountMinor, String currency) {
    return new TrialBalanceLine("4000", "REVENUE", "REVENUE", amountMinor, currency, null, null);
  }

  static TrialBalanceLine expense(long amountMinor, String currency) {
    return new TrialBalanceLine("6000", "EXPENSE", "EXPENSE", amountMinor, currency, null, null);
  }

  /** A plain (non-intercompany) ASSET line — a balance-sheet DEBIT (translated at CLOSING). */
  static TrialBalanceLine asset(long amountMinor, String currency) {
    return new TrialBalanceLine("1000", "ASSET", "ASSET", amountMinor, currency, null, null);
  }

  /** A plain (non-intercompany) EQUITY line — a balance-sheet CREDIT (translated at CLOSING). */
  static TrialBalanceLine equity(long amountMinor, String currency) {
    return new TrialBalanceLine("3000", "EQUITY", "EQUITY", amountMinor, currency, null, null);
  }

  /** An intercompany REVENUE leg (the internal sale) tagged with a counterparty + reference. */
  static TrialBalanceLine intercompanyRevenue(
      long amountMinor, String currency, UUID counterparty, String ref) {
    return new TrialBalanceLine(
        "4900", "REVENUE", "REVENUE", amountMinor, currency, counterparty, ref);
  }

  /** An intercompany EXPENSE leg (the internal purchase) tagged with a counterparty + reference. */
  static TrialBalanceLine intercompanyExpense(
      long amountMinor, String currency, UUID counterparty, String ref) {
    return new TrialBalanceLine(
        "6900", "EXPENSE", "EXPENSE", amountMinor, currency, counterparty, ref);
  }

  /**
   * An intercompany ASSET leg (an IC receivable) tagged with a counterparty + reference — a
   * balance-sheet related-party line, out of 3a's P&amp;L scope.
   */
  static TrialBalanceLine intercompanyReceivable(
      long amountMinor, String currency, UUID counterparty, String ref) {
    return new TrialBalanceLine("1900", "ASSET", "ASSET", amountMinor, currency, counterparty, ref);
  }

  /**
   * An intercompany LIABILITY leg (an IC payable) tagged with a counterparty + reference — a
   * balance-sheet related-party line, out of 3a's P&amp;L scope.
   */
  static TrialBalanceLine intercompanyPayable(
      long amountMinor, String currency, UUID counterparty, String ref) {
    return new TrialBalanceLine(
        "2900", "LIABILITY", "LIABILITY", amountMinor, currency, counterparty, ref);
  }
}
