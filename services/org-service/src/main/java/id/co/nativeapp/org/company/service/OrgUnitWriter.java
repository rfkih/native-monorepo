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
import id.co.nativeapp.org.company.repository.OrgUnitRepository;
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
 * Owns the {@code @Transactional} units of work for the full org tree behind {@link
 * OrgUnitService}: creating a node under a parent ({@code OrgUnitCreated}) and patching one —
 * rename / move / deactivate ({@code OrgUnitChanged}). It is a distinct bean (not private methods
 * on the service) so each method is invoked through the Spring proxy and the {@link
 * RlsAutoApplyAspect} sets the tenant GUC (rule 5) — the same {@code *Writer} pattern {@link
 * CompanyWriter} uses.
 *
 * <p><strong>Hierarchy enforcement (the three checks).</strong>
 *
 * <ul>
 *   <li><em>type rule</em> — the {@link OrgUnit} aggregate validates that the child's type may sit
 *       under the parent's type ({@code business_unit > branch > outlet > team}); the writer loads
 *       the parent to supply its type.
 *   <li><em>same company</em> — a parent in another tenant is invisible under RLS, so {@code
 *       findById} returns empty and the writer rejects an unknown parent with a {@code 400}; the
 *       parent therefore can only ever be one in the same company.
 *   <li><em>no cycle</em> — on a move, the writer walks the prospective parent's ancestor chain and
 *       rejects the move if the node being moved appears in it (a node cannot become its own
 *       ancestor). The aggregate additionally guards a direct self-parent.
 * </ul>
 *
 * <p>The org_unit insert / update and its outbox row commit in ONE transaction (rule 3).
 */
@Component
public class OrgUnitWriter {

  private final OrgUnitRepository orgUnitRepository;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  public OrgUnitWriter(
      OrgUnitRepository orgUnitRepository, OutboxWriter outboxWriter, Clock clock) {
    this.orgUnitRepository = orgUnitRepository;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
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

    OrgUnitType type = OrgUnitType.from(command.type());
    UUID parentId = command.parentId();
    OrgUnit parent = resolveParent(parentId);
    OrgUnitType parentType = parent == null ? null : parent.getType();

    // A node belongs to the same legal_employer as its parent; a top-level node belongs
    // to the company's default legal_employer (id == company_id in the bootstrap).
    UUID legalEmployerId = parent == null ? companyId : parent.getLegalEmployerId();

    // The aggregate enforces the parent->child type rule (and root => null parent).
    OrgUnit orgUnit =
        new OrgUnit(command.name(), type, parentId, parentType, legalEmployerId, today());
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

    if (command.newName() == null && !command.reparent() && !command.deactivate()) {
      throw new IllegalArgumentException("Patch requested no change (rename, move, or deactivate)");
    }

    OrgUnit orgUnit =
        orgUnitRepository
            .findById(command.orgUnitId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown org unit"));

    // Apply the requested operations, emitting one OrgUnitChanged per operation that
    // actually changed state. Order: rename, then move, then deactivate.
    if (command.newName() != null && orgUnit.rename(command.newName())) {
      emitChanged(orgUnit, companyId, OrgUnitChangeKind.RENAMED);
    }

    if (command.reparent()) {
      UUID newParentId = command.newParentId();
      OrgUnit newParent = resolveMoveParent(orgUnit, newParentId);
      OrgUnitType newParentType = newParent == null ? null : newParent.getType();
      if (orgUnit.moveTo(newParentId, newParentType)) {
        emitChanged(orgUnit, companyId, OrgUnitChangeKind.MOVED);
      }
    }

    if (command.deactivate() && orgUnit.deactivate(today())) {
      emitChanged(orgUnit, companyId, OrgUnitChangeKind.DEACTIVATED);
    }

    orgUnitRepository.flush();
    return orgUnit;
  }

  /** Resolves a create-time parent, rejecting an unknown/cross-tenant parent id with a 400. */
  private OrgUnit resolveParent(UUID parentId) {
    if (parentId == null) {
      return null;
    }
    return orgUnitRepository
        .findById(parentId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown parent org unit"));
  }

  /**
   * Resolves a move target's new parent and rejects a cycle. The new parent must be in the same
   * company (RLS makes a cross-tenant parent invisible -> empty -> 400), must not be the node
   * itself, and must not be a descendant of the node (which would create a cycle).
   */
  private OrgUnit resolveMoveParent(OrgUnit moving, UUID newParentId) {
    if (newParentId == null) {
      return null;
    }
    if (newParentId.equals(moving.getId())) {
      throw new IllegalArgumentException("An org unit cannot be its own parent");
    }
    OrgUnit newParent =
        orgUnitRepository
            .findById(newParentId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown parent org unit"));
    // Walk the prospective parent's ancestors; if the moving node is among them, the
    // move would make the node its own ancestor — a cycle. Reject with a 400.
    UUID ancestorId = newParent.getParentId();
    while (ancestorId != null) {
      if (ancestorId.equals(moving.getId())) {
        throw new IllegalArgumentException("Move would create a cycle in the org tree");
      }
      UUID nextId = ancestorId;
      OrgUnit ancestor =
          orgUnitRepository
              .findById(nextId)
              .orElseThrow(() -> new IllegalArgumentException("Broken org-tree ancestry"));
      ancestorId = ancestor.getParentId();
    }
    return newParent;
  }

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

  private LocalDate today() {
    return LocalDate.now(clock);
  }
}
