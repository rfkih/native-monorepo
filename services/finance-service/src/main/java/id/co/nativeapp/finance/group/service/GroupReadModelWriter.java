package id.co.nativeapp.finance.group.service;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.group.domain.GroupMember;
import id.co.nativeapp.finance.group.domain.GroupRef;
import id.co.nativeapp.finance.group.messaging.GroupDefinedEvent;
import id.co.nativeapp.finance.group.messaging.GroupMembershipChangedEvent;
import id.co.nativeapp.finance.group.repository.GroupMemberRepository;
import id.co.nativeapp.finance.group.repository.GroupRefRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit of work that projects org-service group events into
 * finance's local group read model (P3d SEAM 1) — {@link GroupRef} (group → lead + reporting
 * currency) and {@link GroupMember} (the effective-dated active member set) — all idempotently.
 *
 * <p>A distinct bean (not private methods on {@link GroupReadModelService}) so the method is
 * invoked through the Spring proxy: a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets the tenant GUC the RLS
 * {@code WITH CHECK} needs (rule 5). Pure plumbing: NO consolidation math here.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> Each handler runs inside {@link
 * ProcessedEventStore#processOnce}, so the dedupe claim and the read-model upsert commit (or roll
 * back) together; a re-delivered event (same event id) is a clean no-op.
 *
 * <p><strong>Tenant = the group's lead.</strong> Both read-model tables are RLS-scoped to the lead
 * company. {@code GroupDefined} carries the lead in its payload; the membership events do not, so
 * the lead is resolved from the local {@code group_lead} reference mapping ({@link
 * GroupLeadResolver} — rule 2, never a sync call). The handler is bound to that lead by {@link
 * GroupReadModelService} before the transactional write, so the auto-RLS aspect sets the GUC and
 * the WITH CHECK passes.
 */
@Component
public class GroupReadModelWriter {

  private static final Logger log = LoggerFactory.getLogger(GroupReadModelWriter.class);

  private final GroupRefRepository groupRefRepository;
  private final GroupMemberRepository groupMemberRepository;
  private final GroupLeadResolver leadResolver;
  private final MemberGroupResolver memberGroupResolver;
  private final ProcessedEventStore processedEvents;

  public GroupReadModelWriter(
      GroupRefRepository groupRefRepository,
      GroupMemberRepository groupMemberRepository,
      GroupLeadResolver leadResolver,
      MemberGroupResolver memberGroupResolver,
      ProcessedEventStore processedEvents) {
    this.groupRefRepository = groupRefRepository;
    this.groupMemberRepository = groupMemberRepository;
    this.leadResolver = leadResolver;
    this.memberGroupResolver = memberGroupResolver;
    this.processedEvents = processedEvents;
  }

  /**
   * Projects a {@code GroupDefined} into {@link GroupRef} (and the {@code group_lead} reference
   * mapping) exactly once per event id. Must be called inside a {@link TenantContext} scope bound
   * to the event's {@code lead_company_id}.
   *
   * @return {@code true} on the first delivery, {@code false} if skipped as a duplicate.
   */
  @Transactional
  public boolean applyGroupDefined(GroupDefinedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsertGroupRef(event));
  }

  private void upsertGroupRef(GroupDefinedEvent event) {
    String tenant = TenantContext.require().companyId();
    // The group_lead reference mapping (non-RLS) lets the membership consumer later resolve the
    // lead
    // from group_id alone — written here, atomically with group_ref.
    leadResolver.record(event.groupId(), event.leadCompanyId());

    if (groupRefRepository.findById(event.groupId()).isPresent()) {
      // A redelivery that slipped past processOnce (e.g. the dedupe row was cleared) — the group
      // ref
      // is immutable structural data, so leave the existing row untouched.
      log.debug("GroupRef {} already present; GroupDefined re-applied as a no-op", event.groupId());
      return;
    }
    GroupRef ref = new GroupRef(event.groupId(), event.leadCompanyId(), event.reportingCurrency());
    ref.setCompanyId(tenant);
    groupRefRepository.save(ref);
    log.info(
        "Registered group_ref groupId={} lead={} reportingCurrency={}",
        event.groupId(),
        event.leadCompanyId(),
        event.reportingCurrency());
  }

  /**
   * Projects a {@code GroupMembershipChanged} into {@link GroupMember} exactly once per event id.
   * Must be called inside a {@link TenantContext} scope bound to the group's resolved {@code
   * lead_company_id}. The event carries the post-change effective window, so the consumer simply
   * mirrors it (ADDED opens/re-opens, REMOVED closes).
   *
   * @return {@code true} on the first delivery, {@code false} if skipped as a duplicate.
   */
  @Transactional
  public boolean applyMembershipChanged(GroupMembershipChangedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> upsertGroupMember(event));
  }

  private void upsertGroupMember(GroupMembershipChangedEvent event) {
    String tenant = TenantContext.require().companyId();
    // The bound tenant IS the group's lead (resolved from group_lead by GroupReadModelService
    // before
    // this RLS-scoped write), so it is both the company_id stamped below and the lead_company_id
    // the
    // ck_group_member_lead_is_tenant CHECK pins it to.
    UUID lead = UUID.fromString(tenant);
    GroupMember member =
        groupMemberRepository
            .findByGroupIdAndMemberCompanyId(event.groupId(), event.memberCompanyId())
            .orElse(null);
    if (member == null) {
      member =
          new GroupMember(
              event.groupId(),
              event.memberCompanyId(),
              lead,
              event.effectiveFrom(),
              event.effectiveTo());
      member.setCompanyId(tenant);
    } else {
      // Mirror the event's post-change window (idempotent — the event is the source of truth).
      member.applyWindow(event.effectiveFrom(), event.effectiveTo());
    }
    groupMemberRepository.save(member);
    // Mirror the membership window into the non-RLS member->group REVERSE index alongside the
    // LEAD-scoped group_member row, so a within-company close (running as the MEMBER, which cannot
    // read the lead-owned group_member under RLS) can resolve the groups it belongs to (rule 2 —
    // a cached reference, never a sync call). Same post-change window, so it stays consistent.
    memberGroupResolver.record(
        event.memberCompanyId(), event.groupId(), event.effectiveFrom(), event.effectiveTo());
    log.info(
        "Applied {} for groupId={} member={} (active={})",
        event.changeKind(),
        event.groupId(),
        event.memberCompanyId(),
        member.isActive());
  }
}
