package id.co.nativeapp.finance.grouptb.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.grouptb.domain.GroupTrialBalanceLine;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedDecodeException;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent.TrialBalanceLine;
import id.co.nativeapp.finance.grouptb.repository.GroupTrialBalanceLineRepository;
import id.co.nativeapp.finance.mapping.domain.AccountType;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit of work that ingests a consumed {@code
 * TrialBalancePublished} into the group's {@link GroupTrialBalanceLine} read model (P3d SEAM 2),
 * idempotently. SEAM 2 stops at ingest — NO consolidation / elimination math (that is SEAM 3).
 *
 * <p>A distinct bean (not private methods on {@link GroupTrialBalanceIngestService}) so the method
 * is invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets BOTH the {@code
 * app.current_tenant} (the lead) and {@code app.current_group} GUCs the conjunction RLS {@code WITH
 * CHECK} needs (rule 5).
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> The whole ingest runs inside {@link
 * ProcessedEventStore#processOnce}, so the dedupe claim and every line upsert commit (or roll back)
 * together; a re-delivered event (same event id) is a clean no-op. The {@code (source_event_id,
 * gl_account_code, posting_type)} UNIQUE constraint is the database backstop.
 *
 * <p><strong>Tenant = the group's LEAD; group = the event's group_id.</strong> The owning tenant of
 * every {@code group_trial_balance} row is the group's lead company (rule 4), and the line is
 * scoped by the conjunction {@code group_id = current_group AND company_id = current_tenant}.
 * {@link GroupTrialBalanceIngestService} binds {@code (lead, group)} via {@link
 * TenantContext#callAsGroup} before this write, so both GUCs are set and the WITH CHECK passes. The
 * member company is stamped as the {@code member_company_id} DIMENSION — never the tenant.
 */
@Component
public class GroupTrialBalanceWriter {

  private static final Logger log = LoggerFactory.getLogger(GroupTrialBalanceWriter.class);

  private final GroupTrialBalanceLineRepository repository;
  private final ProcessedEventStore processedEvents;

  public GroupTrialBalanceWriter(
      GroupTrialBalanceLineRepository repository, ProcessedEventStore processedEvents) {
    this.repository = repository;
    this.processedEvents = processedEvents;
  }

  /**
   * Ingests a {@code TrialBalancePublished} into {@code group_trial_balance} exactly once per event
   * id. Must be called inside a {@link TenantContext} group scope bound to the group's {@code lead}
   * company AND the event's {@code group_id}.
   *
   * @return {@code true} on the first delivery, {@code false} if skipped as a duplicate.
   */
  @Transactional
  public boolean ingest(TrialBalancePublishedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsertLines(event));
  }

  private void upsertLines(TrialBalancePublishedEvent event) {
    // The bound tenant IS the group's lead company (company_id on every group row, rule 4). The
    // group scope is bound alongside it; both are required for the conjunction WITH CHECK to pass.
    String leadTenant = TenantContext.require().companyId();
    int ingested = 0;
    for (TrialBalanceLine line : event.lines()) {
      // Belt-and-braces against a re-delivery that slipped past processOnce (e.g. the dedupe row
      // was
      // cleared): the UNIQUE (source_event_id, gl_account_code, posting_type) is the DB backstop,
      // but
      // skip an already-present line so the transaction is not poisoned by a unique violation.
      if (repository
          .findBySourceEventIdAndGlAccountCodeAndPostingType(
              event.eventId(), line.glAccountCode(), line.postingType())
          .isPresent()) {
        continue;
      }
      GroupTrialBalanceLine row =
          new GroupTrialBalanceLine(
              event.groupId(),
              event.companyId(),
              event.period(),
              line.glAccountCode(),
              parseAccountType(line.accountType()),
              line.postingType(),
              Money.ofMinor(line.amountMinor(), line.currency()),
              line.relatedPartyCounterpartyId(),
              line.intercompanyRef(),
              event.usesIllustrativeRules(),
              event.reconciled(),
              event.eventId());
      row.setCompanyId(leadTenant);
      repository.save(row);
      ingested++;
    }
    log.info(
        "Ingested {} group_trial_balance line(s) for groupId={} member={} period={} (lead={})",
        ingested,
        event.groupId(),
        event.companyId(),
        event.period(),
        leadTenant);
  }

  private static AccountType parseAccountType(String raw) {
    try {
      return AccountType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new TrialBalancePublishedDecodeException(
          "TrialBalancePublished line carries an unknown account_type: " + raw, unknown);
    }
  }
}
