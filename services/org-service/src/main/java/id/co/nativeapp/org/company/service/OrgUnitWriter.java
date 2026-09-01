package id.co.nativeapp.org.company.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitChangeKind;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.domain.Vertical;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *       under the parent's type ({@code business_unit > outlet > team}); the writer loads the
 *       parent to supply its type.
 *   <li><em>same company</em> — a parent in another tenant is invisible under RLS, so {@code
 *       findById} returns empty and the writer rejects an unknown parent with a {@code 400}; the
 *       parent therefore can only ever be one in the same company.
 *   <li><em>no cycle</em> — on a move, the writer walks the prospective parent's ancestor chain and
 *       rejects the move if the node being moved appears in it (a node cannot become its own
 *       ancestor). The aggregate additionally guards a direct self-parent.
 * </ul>
 *
 * <p><strong>Lifecycle semantics (#25) — the tree invariant: no active node may have an inactive
 * ancestor.</strong> Four structural operations keep it:
 *
 * <ul>
 *   <li><em>deactivate CASCADES</em> — deactivating a node also deactivates every active descendant
 *       (one {@code DEACTIVATED} event each), so an outlet and its teams go down together; no
 *       active node is left orphaned under an inactive parent.
 *   <li><em>reactivate requires an active parent</em> — a node can be reactivated ({@code
 *       REACTIVATED}) only if its parent is active (reactivate top-down); it does NOT cascade to
 *       descendants. {@code deactivate} and {@code reactivate} in one patch is rejected ({@code
 *       400}).
 *   <li><em>move guards the same invariant</em> — an ACTIVE node cannot be moved under an INACTIVE
 *       parent (it would create an orphan); moving an inactive node is unconstrained by this.
 *   <li><em>create guards it too</em> — a new node is always active, so it cannot be created under
 *       an inactive parent (the fourth entry point — see {@link #resolveParent}).
 * </ul>
 *
 * <p>The org_unit insert / update(s) and their outbox row(s) commit in ONE transaction (rule 3) — a
 * cascade deactivation and all its events are atomic.
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
   * PERMANENTLY deletes an org unit and its entire subtree (a business unit and its outlets, or an
   * outlet and its teams) — the "remove a mistake" path. The safe, history-preserving alternative
   * is {@link #patch} with {@code deactivate=true}; deletion is only for a unit created in error.
   *
   * <p><strong>The guard is best-effort, not a guarantee.</strong> It rejects any unit (or
   * descendant) that has — or ever had — an assigned login ({@link OrgUnitHasDataException} → 409),
   * because {@code user_outlet_assignment} is the only sales-linked signal org-service owns
   * locally: assignments only ever target outlets, so an assigned outlet is one a cashier could
   * have rung sales at. The guard matches closed rows too (an unassigned login keeps its row), so
   * an outlet that was ever staffed stays undeletable. It does NOT catch owner/manager-rung sales
   * on a never-assigned outlet — those leave no assignment row and no synchronous cross-service
   * check is permitted (rule 2), so that residual orphan risk is accepted and documented in ADR
   * 0018 (the console additionally blocks on employees before offering delete; the fully-safe path
   * is always deactivate). One {@code OrgUnitDeleted} is emitted per deleted node (ADR 0070),
   * atomically with the row deletes, so finance's and employee's cached org trees PURGE the subtree
   * instead of keeping it forever as inert refs — the follow-up ADR 0018 recorded.
   *
   * <p>The whole subtree is removed in one transaction so no descendant is left pointing at a
   * deleted parent (org-service has no intra-schema FKs — the invariant is enforced here, not by
   * the DB). A BU always seeds a default outlet (ADR 0012), so deleting an empty BU cascades its
   * empty outlets.
   *
   * @throws OrgUnitNotFoundException unknown or cross-tenant unit (→ 404, anti-enumeration)
   * @throws OrgUnitHasDataException the unit or any descendant has (or had) an assigned login (→
   *     409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void delete(UUID orgUnitId) {
    TenantContext.require();
    List<OrgUnit> all = orgUnitRepository.findAll(); // RLS-scoped to the bound tenant
    OrgUnit target =
        all.stream()
            .filter(u -> u.getId().equals(orgUnitId))
            .findFirst()
            .orElseThrow(() -> new OrgUnitNotFoundException(orgUnitId));

    // Collect the target and its full subtree (all transitive descendants), so an outlet's teams
    // — or a BU's outlets and their teams — go with it and nothing is orphaned. The tree is at
    // most business_unit > outlet > team (ADR 0012); the visited set is defence-in-depth against
    // corrupt cyclic data (mirrors cascadeDeactivate) so the walk always terminates.
    List<OrgUnit> toDelete = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    Deque<OrgUnit> pending = new ArrayDeque<>();
    pending.push(target);
    while (!pending.isEmpty()) {
      OrgUnit node = pending.pop();
      if (!visited.add(node.getId())) {
        continue;
      }
      toDelete.add(node);
      for (OrgUnit u : all) {
        if (node.getId().equals(u.getParentId())) {
          pending.push(u);
        }
      }
    }

    for (OrgUnit u : toDelete) {
      if (userOutletAssignmentRepository.existsByOrgUnitId(u.getId())) {
        throw new OrgUnitHasDataException(u.getId());
      }
    }

    // Emit BEFORE the delete, while the aggregates still hold their state — the outbox rows go on
    // this transaction's connection, so the events and the row deletes commit together (rule 3).
    // A rollback drops both; there is no window where a consumer purges a unit that still exists.
    UUID companyId = UUID.fromString(TenantContext.require().companyId());
    for (OrgUnit u : toDelete) {
      emitDeleted(u, companyId);
    }

    orgUnitRepository.deleteAll(toDelete);
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

    OrgUnitType type = OrgUnitType.from(command.type());
    UUID parentId = command.parentId();
    OrgUnit parent = resolveParent(parentId);
    OrgUnitType parentType = parent == null ? null : parent.getType();

    // A node belongs to the same legal_employer as its parent; a top-level node belongs
    // to the company's default legal_employer (id == company_id in the bootstrap).
    UUID legalEmployerId = parent == null ? companyId : parent.getLegalEmployerId();

    // The aggregate enforces the parent->child type rule (and root => null parent) plus the
    // vertical rule (required for a BUSINESS_UNIT, forbidden otherwise).
    Vertical vertical = command.vertical() == null ? null : Vertical.fromKey(command.vertical());
    OrgUnit orgUnit =
        new OrgUnit(command.name(), type, vertical, parentId, parentType, legalEmployerId, today());
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

    // ADR 0012: EVERY new business unit seeds one default outlet — including a root
    // BUSINESS_UNIT created through this endpoint (the org-tree page), not just the
    // company-bootstrap / add-business paths in CompanyWriter. Same transaction, so the
    // outlet row and its OrgUnitCreated commit atomically with the business unit.
    if (type == OrgUnitType.BUSINESS_UNIT) {
      OrgUnit outlet =
          new OrgUnit(
              saved.getName(),
              OrgUnitType.OUTLET,
              null,
              saved.getId(),
              OrgUnitType.BUSINESS_UNIT,
              saved.getLegalEmployerId(),
              today());
      outlet.setCompanyId(tenant);
      OrgUnit savedOutlet = orgUnitRepository.save(outlet);
      orgUnitRepository.flush();
      GenericRecord outletEvent = OrgUnitCreatedSchema.toRecord(savedOutlet);
      outboxWriter.write(
          OrgUnitCreatedSchema.AGGREGATE_TYPE,
          savedOutlet.getId().toString(),
          OrgUnitCreatedSchema.EVENT_TYPE,
          AvroSerde.serialize(outletEvent),
          null,
          companyId,
          savedOutlet.getCreatedAt());
    }
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

    if (command.reparent()) {
      UUID newParentId = command.newParentId();
      OrgUnit newParent = resolveMoveParent(orgUnit, newParentId);
      OrgUnitType newParentType = newParent == null ? null : newParent.getType();
      if (orgUnit.moveTo(newParentId, newParentType)) {
        emitChanged(orgUnit, companyId, OrgUnitChangeKind.MOVED);
      }
    }

    if (command.deactivate()) {
      cascadeDeactivate(orgUnit, companyId);
    }

    if (command.reactivate()) {
      requireReactivatableParent(orgUnit);
      if (orgUnit.reactivate()) {
        emitChanged(orgUnit, companyId, OrgUnitChangeKind.REACTIVATED);
      }
    }

    orgUnitRepository.flush();
    return orgUnit;
  }

  /**
   * Deactivates {@code root} and every ACTIVE node in its subtree, emitting one {@code DEACTIVATED}
   * event per node that changed — so deactivating an outlet takes its teams down with it (the tree
   * invariant: no active node left under an inactive ancestor, #25). The subtree is walked over the
   * bound tenant's org units ({@code findAll} is RLS-scoped; an org tree is small), parent →
   * children, breadth-first. A {@code visited} set makes it cycle-safe even though the writer
   * already prevents cycles. Already-inactive descendants are skipped (no event). All deactivations
   * and their events commit in the one patch transaction.
   */
  private void cascadeDeactivate(OrgUnit root, UUID companyId) {
    LocalDate asOf = today();
    Map<UUID, List<OrgUnit>> childrenByParent = new HashMap<>();
    for (OrgUnit unit : orgUnitRepository.findAll()) {
      if (unit.getParentId() != null) {
        childrenByParent.computeIfAbsent(unit.getParentId(), key -> new ArrayList<>()).add(unit);
      }
    }
    Deque<OrgUnit> queue = new ArrayDeque<>();
    queue.add(root);
    Set<UUID> visited = new HashSet<>();
    while (!queue.isEmpty()) {
      OrgUnit node = queue.poll();
      if (!visited.add(node.getId())) {
        continue;
      }
      if (node.deactivate(asOf)) {
        emitChanged(node, companyId, OrgUnitChangeKind.DEACTIVATED);
      }
      childrenByParent.getOrDefault(node.getId(), List.of()).forEach(queue::add);
    }
  }

  /**
   * Rejects reactivating a node whose parent is inactive (the tree invariant: no active node may
   * have an inactive ancestor — reactivate the parent first). A root node (no parent) is always
   * reactivatable. The parent is read under RLS, so a cross-tenant parent is invisible.
   *
   * <p>Checking only the IMMEDIATE parent is sufficient: the invariant holds for every
   * already-active node, so an active parent already has an active ancestor chain. Reactivating
   * top-down therefore keeps the chain valid at each step.
   */
  private void requireReactivatableParent(OrgUnit node) {
    UUID parentId = node.getParentId();
    if (parentId == null) {
      return;
    }
    OrgUnit parent =
        orgUnitRepository
            .findById(parentId)
            .orElseThrow(() -> new IllegalArgumentException("Broken org-tree ancestry"));
    if (!parent.isActive()) {
      throw new IllegalArgumentException(
          "Cannot reactivate an org unit whose parent is inactive; reactivate the parent first");
    }
  }

  /**
   * Resolves a create-time parent, rejecting an unknown/cross-tenant parent id with a 400, and an
   * INACTIVE parent with a 400. A newly created node is always active, so creating it under an
   * inactive parent would orphan an active node under an inactive ancestor — the same tree
   * invariant the move guard enforces (#25). This closes the create entry point so all four
   * structural ops (create, move, deactivate, reactivate) keep the invariant.
   */
  private OrgUnit resolveParent(UUID parentId) {
    if (parentId == null) {
      return null;
    }
    OrgUnit parent =
        orgUnitRepository
            .findById(parentId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown parent org unit"));
    if (!parent.isActive()) {
      throw new IllegalArgumentException("Cannot create an org unit under an inactive parent");
    }
    return parent;
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
    // An ACTIVE node cannot be moved under an INACTIVE parent — it would orphan an active node
    // under
    // an inactive ancestor (the tree invariant, #25). Moving an inactive node is unconstrained
    // here.
    if (moving.isActive() && !newParent.isActive()) {
      throw new IllegalArgumentException("Cannot move an active org unit under an inactive parent");
    }
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
