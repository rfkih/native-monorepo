package id.co.nativeapp.org.group.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.org.group.domain.ConsolidationGroup;
import id.co.nativeapp.org.group.domain.GroupMembership;
import id.co.nativeapp.org.group.domain.GroupMembershipChangeKind;
import id.co.nativeapp.org.group.dto.AddMemberCommand;
import id.co.nativeapp.org.group.dto.AddMemberResult;
import id.co.nativeapp.org.group.dto.CreateGroupCommand;
import id.co.nativeapp.org.group.dto.RemoveMemberCommand;
import id.co.nativeapp.org.group.messaging.GroupDefinedSchema;
import id.co.nativeapp.org.group.messaging.GroupMembershipChangedSchema;
import id.co.nativeapp.org.group.repository.ConsolidationGroupRepository;
import id.co.nativeapp.org.group.repository.GroupMembershipRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for the consolidation-group feature behind {@link
 * GroupService}: defining a group ({@code GroupDefined}) and adding / removing a member ({@code
 * GroupMembershipChanged}). It is a distinct bean (not private methods on the service) so each
 * method is invoked through the Spring proxy and the {@link RlsAutoApplyAspect} sets the tenant GUC
 * (rule 5) — the same {@code *Writer} pattern {@link
 * id.co.nativeapp.org.company.service.OrgUnitWriter} uses.
 *
 * <p><strong>Lead-owned, RLS-confined.</strong> Both tables are scoped to the LEAD company ({@code
 * company_id = lead_company_id}). The writer stamps {@code company_id} from the bound {@link
 * TenantContext} (the caller's tenant = the lead), so the group can only ever be
 * created/administered by its lead, and a member company — querying its own scope — sees nothing. A
 * group/membership lookup for a {@code groupId} the bound tenant does not lead returns empty
 * (invisible under RLS), so the writer rejects it with a {@code 400}.
 *
 * <p>The group/membership insert (or update) and its outbox row commit in ONE transaction (rule 3).
 * Events fire on REAL state transitions only — a re-add of an already-active member, or a re-remove
 * of an already-closed membership, is a no-op that emits nothing (idempotent producer).
 */
@Component
public class GroupWriter {

  private final ConsolidationGroupRepository groupRepository;
  private final GroupMembershipRepository membershipRepository;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  public GroupWriter(
      ConsolidationGroupRepository groupRepository,
      GroupMembershipRepository membershipRepository,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.groupRepository = groupRepository;
    this.membershipRepository = membershipRepository;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * Creates a consolidation group led by the bound tenant, with its immutable reporting currency,
   * and emits exactly one {@code GroupDefined} to the outbox — atomically.
   *
   * @param command the create command (name, reporting currency)
   * @return the persisted group
   * @throws IllegalArgumentException if the reporting currency is not a valid ISO-4217 code or the
   *     name is blank (mapped to {@code 400})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ConsolidationGroup createGroup(CreateGroupCommand command) {
    String tenant = TenantContext.require().companyId();
    UUID leadCompanyId = UUID.fromString(tenant);

    // The aggregate validates the reporting currency (ISO-4217) and makes it immutable; an unknown
    // code throws IllegalArgumentException -> 400. The lead is the bound tenant, never the body.
    ConsolidationGroup group =
        new ConsolidationGroup(leadCompanyId, command.name(), command.reportingCurrency());
    group.setCompanyId(tenant);
    ConsolidationGroup saved = groupRepository.save(group);
    // Flush so the insert (and any RLS WITH CHECK violation) surfaces inside this transaction.
    groupRepository.flush();

    GenericRecord event = GroupDefinedSchema.toRecord(saved);
    outboxWriter.write(
        GroupDefinedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        GroupDefinedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        leadCompanyId,
        clock.instant());
    return saved;
  }

  /**
   * Adds a member company to a group the bound tenant leads, opening a fresh effective-dated
   * membership, and emits one {@code GroupMembershipChanged(ADDED)} — atomically. Idempotent: if
   * the company already has an ACTIVE membership in the group, this is a no-op (no row, no event).
   *
   * @param command the add-member command (group id from the path, member company id)
   * @return the add-member outcome: the active membership and whether it was newly created (a no-op
   *     re-add of an already-active member returns the existing row with {@code created = false})
   * @throws IllegalArgumentException if the group is unknown / not led by the bound tenant (mapped
   *     to {@code 400})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AddMemberResult addMember(AddMemberCommand command) {
    String tenant = TenantContext.require().companyId();
    UUID leadCompanyId = UUID.fromString(tenant);

    // The group must be visible (= led by the bound tenant) under RLS; otherwise reject with a 400
    // rather than letting a member smuggle a membership into a group it does not lead. The loaded
    // group gives us the lead to stamp structurally on the new membership row (FIX 1).
    ConsolidationGroup group = requireOwnedGroup(command.groupId());

    // Idempotency: an already-active membership is not re-added (no duplicate active row, no
    // event) — a no-op the adapter maps to 200, not 201.
    GroupMembership existingActive =
        membershipRepository
            .findByGroupIdAndMemberCompanyIdAndEffectiveTo(
                command.groupId(), command.memberCompanyId(), GroupMembership.OPEN_ENDED)
            .orElse(null);
    if (existingActive != null) {
      return new AddMemberResult(existingActive, false);
    }

    GroupMembership membership =
        new GroupMembership(
            command.groupId(), command.memberCompanyId(), group.getLeadCompanyId(), today());
    membership.setCompanyId(tenant);
    GroupMembership saved = membershipRepository.save(membership);
    membershipRepository.flush();

    emitMembershipChanged(saved, leadCompanyId, GroupMembershipChangeKind.ADDED);
    return new AddMemberResult(saved, true);
  }

  /**
   * Removes a member company from a group the bound tenant leads by CLOSING the membership's
   * effective window (no hard-delete), and emits one {@code GroupMembershipChanged(REMOVED)} —
   * atomically. Idempotent: removing a company that has no active membership is a no-op (no event);
   * the closed row's history is preserved.
   *
   * @param command the remove-member command (group id + member company id from the path)
   * @return the closed membership, or {@code null} if there was no active membership to close
   * @throws IllegalArgumentException if the group is unknown / not led by the bound tenant (mapped
   *     to {@code 400})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public GroupMembership removeMember(RemoveMemberCommand command) {
    String tenant = TenantContext.require().companyId();
    UUID leadCompanyId = UUID.fromString(tenant);

    requireOwnedGroup(command.groupId());

    GroupMembership active =
        membershipRepository
            .findByGroupIdAndMemberCompanyIdAndEffectiveTo(
                command.groupId(), command.memberCompanyId(), GroupMembership.OPEN_ENDED)
            .orElse(null);
    if (active == null || !active.close(today())) {
      // No active membership to close — a re-remove is a clean no-op (no event).
      return active;
    }
    GroupMembership saved = membershipRepository.save(active);
    membershipRepository.flush();

    emitMembershipChanged(saved, leadCompanyId, GroupMembershipChangeKind.REMOVED);
    return saved;
  }

  /**
   * Loads a group the bound tenant must lead, returning it (its {@code lead_company_id} is stamped
   * structurally onto a new membership row). A group whose lead is a different tenant is invisible
   * under RLS, so {@code findById} returns empty and we reject the request with a {@code 400} — a
   * member company can therefore neither enumerate nor mutate another lead's group.
   */
  private ConsolidationGroup requireOwnedGroup(UUID groupId) {
    return groupRepository
        .findById(groupId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown consolidation group"));
  }

  private void emitMembershipChanged(
      GroupMembership membership, UUID leadCompanyId, GroupMembershipChangeKind kind) {
    GenericRecord event = GroupMembershipChangedSchema.toRecord(membership, kind);
    // Partition key is the group id (the aggregate id), so a group's membership events are ordered.
    outboxWriter.write(
        GroupMembershipChangedSchema.AGGREGATE_TYPE,
        membership.getGroupId().toString(),
        GroupMembershipChangedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        leadCompanyId,
        clock.instant());
  }

  /** Today's date (UTC), via the injected clock, for effective-dating membership windows. */
  private LocalDate today() {
    return LocalDate.now(clock);
  }
}
