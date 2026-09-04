package id.co.nativeapp.org.company.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitChangeKind;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.dto.PatchOrgUnitCommand;
import id.co.nativeapp.org.company.messaging.OrgUnitChangedSchema;
import id.co.nativeapp.org.company.messaging.OrgUnitCreatedSchema;
import id.co.nativeapp.org.company.messaging.OrgUnitDeletedSchema;
import id.co.nativeapp.org.company.repository.OrgUnitRepository;
import id.co.nativeapp.org.user.repository.UserOutletAssignmentRepository;
import id.co.nativeapp.org.user.service.OrgUnitNotFoundException;
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
 * Owns the {@code @Transactional} units of work for a company's outlets behind {@link
 * OrgUnitService}: creating one ({@code OrgUnitCreated}), patching one — rename / deactivate /
 * reactivate ({@code OrgUnitChanged}) — and permanently deleting one ({@code OrgUnitDeleted}). It
 * is a distinct bean (not private methods on the service) so each method is invoked through the
 * Spring proxy and the {@link RlsAutoApplyAspect} sets the tenant GUC (rule 5) — the same {@code
 * *Writer} pattern {@link CompanyWriter} uses.
 *
 * <p><strong>There is no hierarchy left to enforce (ADR 0070).</strong> The tree is flat — {@code
 * company > outlet} — so the parent→child type rules, the same-company parent check, the cycle
 * walk, the cascading deactivation and the "no active node under an inactive ancestor" invariant
 * are all gone with the division and team levels. What remains is one guard: an outlet has no
 * parent, so a create or patch that supplies one is rejected with a {@code 400} rather than
 * silently ignored (an old client learns its request was not honoured).
 *
 * <p>Every org_unit write and its outbox row(s) commit in ONE transaction (rule 3).
 */
@Component
public class OrgUnitWriter {

  private final OrgUnitRepository orgUnitRepository;
  private final UserOutletAssignmentRepository userOutletAssignmentRepository;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  public OrgUnitWriter(
      OrgUnitRepository orgUnitRepository,
      UserOutletAssignmentRepository userOutletAssignmentRepository,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.orgUnitRepository = orgUnitRepository;
    this.userOutletAssignmentRepository = userOutletAssignmentRepository;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * PERMANENTLY deletes an outlet — the "remove a mistake" path. The safe, history-preserving
   * alternative is {@link #patch} with {@code deactivate=true}; deletion is only for a unit created
   * in error. Since ADR 0070 the tree is flat, so there is no subtree to cascade into: one unit,
   * one delete, one event.
   *
   * <p><strong>The guard is best-effort, not a guarantee.</strong> It rejects a unit that has — or
   * ever had — an assigned login ({@link OrgUnitHasDataException} → 409), because {@code
   * user_outlet_assignment} is the only sales-linked signal org-service owns locally: an assigned
   * outlet is one a cashier could have rung sales at. The guard matches closed rows too (an
   * unassigned login keeps its row), so an outlet that was ever staffed stays undeletable. It does
   * NOT catch owner/manager-rung sales on a never-assigned outlet — those leave no assignment row
   * and no synchronous cross-service check is permitted (rule 2), so that residual orphan risk is
   * accepted and documented in ADR 0018 (the console additionally blocks on employees before
   * offering delete; the fully-safe path is always deactivate).
   *
   * <p>One {@code OrgUnitDeleted} is emitted (ADR 0070), atomically with the row delete, so
   * finance's and employee's cached org trees PURGE the unit instead of keeping it forever as an
   * inert ref — the follow-up ADR 0018 recorded.
   *
   * @throws OrgUnitNotFoundException unknown or cross-tenant unit (→ 404, anti-enumeration)
   * @throws OrgUnitHasDataException the unit has (or had) an assigned login (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void delete(UUID orgUnitId) {
    UUID companyId = UUID.fromString(TenantContext.require().companyId());
    // RLS-scoped: a cross-tenant unit is invisible and resolves exactly like an unknown one.
    OrgUnit target =
        orgUnitRepository
            .findById(orgUnitId)
            .orElseThrow(() -> new OrgUnitNotFoundException(orgUnitId));

    if (userOutletAssignmentRepository.existsByOrgUnitId(target.getId())) {
      throw new OrgUnitHasDataException(target.getId());
    }

    // Emit BEFORE the delete, while the aggregate still holds its state — the outbox row goes on
    // this transaction's connection, so the event and the row delete commit together (rule 3).
    // A rollback drops both; there is no window where a consumer purges a unit that still exists.
    emitDeleted(target, companyId);

    orgUnitRepository.delete(target);
    orgUnitRepository.flush();
  }

  /**
   * Creates an org unit under the bound company, validating the hierarchy, and emits exactly one
   * {@code OrgUnitCreated} to the outbox — atomically.
   *
   * @param command the create command (name, type, optional parent)
   * @return the persisted org unit
   * @throws IllegalArgumentException if the type is unknown, the parent is unknown/cross-tenant, or
   *     the parent→child type rule is violated (all mapped to {@code 400})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OrgUnit create(CreateOrgUnitCommand command) {
    String tenant = TenantContext.require().companyId();
    UUID companyId = UUID.fromString(tenant);

    // ADR 0070: the only kind is OUTLET. A request naming a removed level (business_unit / team)
    // fails here with a 400 rather than silently creating something else.
    OrgUnitType type =
        command.type() == null || command.type().isBlank()
            ? OrgUnitType.OUTLET
            : OrgUnitType.from(command.type());

    // The tree is flat, so an outlet has no parent. An old client still sending one gets an
    // explicit 400 instead of having its intent silently dropped.
    if (command.parentId() != null) {
      throw new IllegalArgumentException(
          "An outlet has no parent — the org tree is flat (company > outlet, ADR 0070)");
    }

    // Every outlet belongs to the company's default legal_employer (id == company_id in the
    // bootstrap); with no parent to inherit from, that is the only source.
    OrgUnit orgUnit = new OrgUnit(command.name(), type, companyId, today());
    orgUnit.setCompanyId(tenant);
    OrgUnit saved = orgUnitRepository.save(orgUnit);
    orgUnitRepository.flush();

    GenericRecord event = OrgUnitCreatedSchema.toRecord(saved);
    outboxWriter.write(
        OrgUnitCreatedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        OrgUnitCreatedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        companyId,
        saved.getCreatedAt());

    return saved;
  }

  /**
   * Patches an org unit (rename / move / deactivate) and emits one {@code OrgUnitChanged} per
   * effective change — atomically with the update. A patch that requests nothing, targets an
   * unknown node, or asks for an illegal move is rejected with a {@code 400}.
   *
   * @param command the patch command
   * @return the org unit in its post-change state
   * @throws IllegalArgumentException on an empty patch, unknown target/parent, illegal move, or
   *     cycle (mapped to {@code 400})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OrgUnit patch(PatchOrgUnitCommand command) {
    String tenant = TenantContext.require().companyId();
    UUID companyId = UUID.fromString(tenant);

    if (command.newName() == null
        && !command.reparent()
        && !command.deactivate()
        && !command.reactivate()) {
      throw new IllegalArgumentException(
          "Patch requested no change (rename, move, deactivate, or reactivate)");
    }
    if (command.deactivate() && command.reactivate()) {
      throw new IllegalArgumentException("A patch cannot both deactivate and reactivate a node");
    }

    OrgUnit orgUnit =
        orgUnitRepository
            .findById(command.orgUnitId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown org unit"));

    // Apply the requested operations, emitting one OrgUnitChanged per node that actually changed
    // state. Order: rename, then move, then deactivate (cascading) / reactivate.
    if (command.newName() != null && orgUnit.rename(command.newName())) {
      emitChanged(orgUnit, companyId, OrgUnitChangeKind.RENAMED);
    }

    // ADR 0070: with a flat tree there is nowhere to move an outlet TO. An old client still asking
    // is told so explicitly rather than having the request silently ignored.
    if (command.reparent()) {
      throw new IllegalArgumentException(
          "An outlet cannot be moved — the org tree is flat (company > outlet, ADR 0070)");
    }

    // No subtree to cascade into, and no ancestor chain to keep valid: one node, one event.
    if (command.deactivate() && orgUnit.deactivate(today())) {
      emitChanged(orgUnit, companyId, OrgUnitChangeKind.DEACTIVATED);
    }

    if (command.reactivate() && orgUnit.reactivate()) {
      emitChanged(orgUnit, companyId, OrgUnitChangeKind.REACTIVATED);
    }

    orgUnitRepository.flush();
    return orgUnit;
  }

  /**
   * Writes one {@code OrgUnitChanged} outbox row for a node that actually changed state, on the
   * caller's transactional connection so it commits atomically with the update (rule 3).
   */
  private void emitChanged(OrgUnit orgUnit, UUID companyId, OrgUnitChangeKind kind) {
    GenericRecord event = OrgUnitChangedSchema.toRecord(orgUnit, kind);
    // occurred_at from the clock: @LastModifiedDate is only stamped on flush, so the
    // entity's updatedAt can still be null while we build the event mid-transaction.
    outboxWriter.write(
        OrgUnitChangedSchema.AGGREGATE_TYPE,
        orgUnit.getId().toString(),
        OrgUnitChangedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        companyId,
        clock.instant());
  }

  /**
   * Writes one {@code OrgUnitDeleted} outbox row for a node being permanently removed (ADR 0070),
   * on the caller's transactional connection so it commits atomically with the row delete (rule 3).
   * Terminal for the aggregate — no event ever follows it for this {@code org_unit_id}.
   */
  void emitDeleted(OrgUnit orgUnit, UUID companyId) {
    GenericRecord event = OrgUnitDeletedSchema.toRecord(orgUnit);
    outboxWriter.write(
        OrgUnitDeletedSchema.AGGREGATE_TYPE,
        orgUnit.getId().toString(),
        OrgUnitDeletedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        companyId,
        clock.instant());
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }
}
