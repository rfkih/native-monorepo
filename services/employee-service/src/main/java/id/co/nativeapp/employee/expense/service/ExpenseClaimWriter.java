package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.assignment.domain.Assignment;
import id.co.nativeapp.employee.assignment.repository.AssignmentRepository;
import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.domain.CategoryNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.ExpenseClaimEvent;
import id.co.nativeapp.employee.expense.domain.SelfApprovalException;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.messaging.ExpenseClaimApprovedSchema;
import id.co.nativeapp.employee.expense.repository.ExpenseCategoryRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimEventRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.payroll.service.PayrollRunWriter;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for the {@link ExpenseClaim} aggregate (design ADR
 * 0030). A distinct bean (the {@code *Writer} pattern) so each method is invoked through the Spring
 * proxy and the {@link RlsAutoApplyAspect} sets the tenant GUC (rule 5); {@code REQUIRES_NEW} on
 * every method keeps a concurrency-collision recovery re-read (in {@link ExpenseClaimService}) in a
 * transaction independent of the aborted one (a PostgreSQL transaction is poisoned once a
 * constraint fires — the {@code SaleWriter}/{@code AssignmentWriter} idiom).
 *
 * <p><strong>Idempotency (ADR 0030 §5).</strong> {@code submit}/{@code cancel}/{@code approve}/
 * {@code refuse} each take an {@code idempotencyKey}: the method first probes {@link
 * ExpenseClaimEventRepository#findIdByClaimIdAndIdempotencyKey} — a hit means this exact transition
 * already ran, so the method returns the claim UNCHANGED (no second mutation, no second outbox
 * write); a miss proceeds to mutate + append the audit row, and the per-tenant {@code UNIQUE
 * (company_id, claim_id, idempotency_key)} (V9) is the concurrency backstop a concurrent racer's
 * insert trips as a {@link org.springframework.dao.DataIntegrityViolationException} — {@link
 * ExpenseClaimService} recovers it with a separate-transaction re-read. {@code create}/ {@code
 * updateDraft} are NOT guarded transitions in the domain sense (they do not change {@link
 * ClaimStatus}) and take no idempotency key.
 *
 * <p><strong>Currency (v1 boundary, ADR 0030 §9).</strong> employee-service has NO persisted source
 * of a company base currency: {@code payroll_run.base_currency} is set purely from the caller's
 * {@code RunPayrollRequest} body on every run, never read from anywhere else (there is no
 * company/settings table in this service). So, unlike a service with a stored base currency to
 * validate against, a claim's currency is instead validated for INTERNAL consistency: the first
 * claim ever created in a tenant establishes that tenant's expense-claim currency, and every
 * subsequent create/edit must match it exactly (checked via {@link
 * ExpenseClaimRepository#findAnyCurrency()}) — else {@link IllegalArgumentException} (→ 400,
 * reusing the same code path {@code Money.ofMinor} already uses for an unknown ISO-4217 code, for
 * one consistent client contract).
 */
@Component
public class ExpenseClaimWriter {

  private final ExpenseClaimRepository claimRepository;
  private final ExpenseCategoryRepository categoryRepository;
  private final ExpenseClaimEventRepository eventRepository;
  private final AssignmentRepository assignmentRepository;
  private final EmployeeRepository employeeRepository;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public ExpenseClaimWriter(
      ExpenseClaimRepository claimRepository,
      ExpenseCategoryRepository categoryRepository,
      ExpenseClaimEventRepository eventRepository,
      AssignmentRepository assignmentRepository,
      EmployeeRepository employeeRepository,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.claimRepository = claimRepository;
    this.categoryRepository = categoryRepository;
    this.eventRepository = eventRepository;
    this.assignmentRepository = assignmentRepository;
    this.employeeRepository = employeeRepository;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * Creates a DRAFT claim for the CALLER (resolved from {@link TenantContext} — never an id
   * parameter, rule 5 / the {@code /me} idiom): validates the category exists and is active,
   * enforces the tenant's currency, and resolves the {@code org_unit_id} snapshot.
   *
   * @throws EmployeeNotLinkedException if the caller has no employee link (→ 404)
   * @throws CategoryNotFoundException if the category is unknown/inactive (→ 404)
   * @throws IllegalArgumentException if the amount/currency is invalid, or the currency does not
   *     match the tenant's established currency (→ 400)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExpenseClaim create(CreateClaimCommand command) {
    String tenant = TenantContext.require().companyId();
    Employee me = resolveMe();

    ExpenseCategory category = requireActiveCategory(command.categoryId());
    Money amount = Money.ofMinor(command.amountMinor(), command.currency());
    requireConsistentCurrency(amount);
    UUID orgUnitId = resolveOrgUnitSnapshot(me.getId(), command.expenseDate());

    ExpenseClaim claim =
        new ExpenseClaim(
            me.getId(),
            category.getId(),
            orgUnitId,
            amount,
            command.expenseDate(),
            command.merchant(),
            command.note(),
            command.reimbursementMethod());
    claim.setCompanyId(tenant);
    return claimRepository.save(claim);
  }

  /**
   * Replaces every editable field of the CALLER's own DRAFT claim.
   *
   * @throws ClaimNotFoundException if the claim is unknown, or not the caller's own (→ 404)
   * @throws id.co.nativeapp.employee.expense.domain.ClaimStateException if not DRAFT (→ 409)
   * @throws CategoryNotFoundException if the category is unknown/inactive (→ 404)
   * @throws IllegalArgumentException if the amount/currency is invalid or currency-inconsistent (→
   *     400)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExpenseClaim updateDraft(UUID claimId, CreateClaimCommand command) {
    Employee me = resolveMe();
    ExpenseClaim claim = requireOwnClaim(claimId, me.getId());

    ExpenseCategory category = requireActiveCategory(command.categoryId());
    Money amount = Money.ofMinor(command.amountMinor(), command.currency());
    requireConsistentCurrency(amount);
    UUID orgUnitId = resolveOrgUnitSnapshot(me.getId(), command.expenseDate());

    claim.applyDraftEdit(
        category.getId(),
        orgUnitId,
        amount,
        command.expenseDate(),
        command.merchant(),
        command.note(),
        command.reimbursementMethod());
    return claimRepository.save(claim);
  }

  /**
   * Submits the CALLER's own DRAFT claim for approval.
   *
   * @throws ClaimNotFoundException if the claim is unknown, or not the caller's own (→ 404)
   * @throws id.co.nativeapp.employee.expense.domain.ClaimStateException if not DRAFT (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExpenseClaim submit(UUID claimId, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();
    Employee me = resolveMe();

    Optional<UUID> replay =
        eventRepository.findIdByClaimIdAndIdempotencyKey(claimId, idempotencyKey);
    if (replay.isPresent()) {
      return requireOwnClaim(claimId, me.getId());
    }

    ExpenseClaim claim = requireOwnClaim(claimId, me.getId());
    ClaimStatus from = claim.getStatus();
    claim.submit();
    claimRepository.save(claim);
    appendEvent(claim, "SUBMIT", from, actor, null, idempotencyKey, tenant);
    return claim;
  }

  /**
   * Cancels the CALLER's own DRAFT or SUBMITTED claim.
   *
   * @throws ClaimNotFoundException if the claim is unknown, or not the caller's own (→ 404)
   * @throws id.co.nativeapp.employee.expense.domain.ClaimStateException if neither DRAFT nor
   *     SUBMITTED (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExpenseClaim cancel(UUID claimId, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();
    Employee me = resolveMe();

    Optional<UUID> replay =
        eventRepository.findIdByClaimIdAndIdempotencyKey(claimId, idempotencyKey);
    if (replay.isPresent()) {
      return requireOwnClaim(claimId, me.getId());
    }

    ExpenseClaim claim = requireOwnClaim(claimId, me.getId());
    ClaimStatus from = claim.getStatus();
    claim.cancel();
    claimRepository.save(claim);
    appendEvent(claim, "CANCEL", from, actor, null, idempotencyKey, tenant);
    return claim;
  }

  /**
   * Approves a SUBMITTED claim: stamps the decision, resolves the category's CURRENT {@code
   * gl_hint}, and writes the {@code ExpenseClaimApproved} outbox row — atomically with the state
   * flip (rule 3). Self-approval (the approver's own claim) is rejected, owners included.
   *
   * @throws ClaimNotFoundException if the claim is unknown in this tenant (→ 404)
   * @throws SelfApprovalException if the approver is the claim's own employee (→ 403)
   * @throws id.co.nativeapp.employee.expense.domain.ClaimStateException if not SUBMITTED (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExpenseClaim approve(UUID claimId, String comment, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    Optional<UUID> replay =
        eventRepository.findIdByClaimIdAndIdempotencyKey(claimId, idempotencyKey);
    if (replay.isPresent()) {
      return claimRepository
          .findById(claimId)
          .orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    ExpenseClaim claim =
        claimRepository.findById(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));

    employeeRepository
        .findByUserId(actor)
        .ifPresent(
            approver -> {
              if (approver.getId().equals(claim.getEmployeeId())) {
                throw new SelfApprovalException(claim.getId());
              }
            });

    ClaimStatus from = claim.getStatus();
    Instant now = clock.instant();
    claim.approve(actor, comment, now);
    claimRepository.save(claim);

    ExpenseCategory category =
        categoryRepository
            .findById(claim.getCategoryId())
            .orElseThrow(() -> new CategoryNotFoundException(claim.getCategoryId()));
    GenericRecord record = ExpenseClaimApprovedSchema.toRecord(claim, category.getGlHint(), now);
    outboxWriter.write(
        ExpenseClaimApprovedSchema.AGGREGATE_TYPE,
        claim.getId().toString(),
        ExpenseClaimApprovedSchema.EVENT_TYPE,
        AvroSerde.serialize(record),
        null,
        UUID.fromString(tenant),
        now);

    appendEvent(claim, "APPROVE", from, actor, comment, idempotencyKey, tenant);
    return claim;
  }

  /**
   * Refuses a SUBMITTED claim: stamps the decision (comment required by the aggregate). No outbox
   * event — refusal never touches the books (only approve/void/settle do, ADR 0030).
   *
   * @throws ClaimNotFoundException if the claim is unknown in this tenant (→ 404)
   * @throws id.co.nativeapp.employee.expense.domain.ClaimStateException if not SUBMITTED (→ 409)
   * @throws id.co.nativeapp.employee.expense.domain.RefusalCommentRequiredException if {@code
   *     comment} is blank (→ 422)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExpenseClaim refuse(UUID claimId, String comment, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    Optional<UUID> replay =
        eventRepository.findIdByClaimIdAndIdempotencyKey(claimId, idempotencyKey);
    if (replay.isPresent()) {
      return claimRepository
          .findById(claimId)
          .orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    ExpenseClaim claim =
        claimRepository.findById(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    ClaimStatus from = claim.getStatus();
    Instant now = clock.instant();
    claim.refuse(actor, comment, now);
    claimRepository.save(claim);
    appendEvent(claim, "REFUSE", from, actor, comment, idempotencyKey, tenant);
    return claim;
  }

  /**
   * Re-reads a claim by id in a FRESH transaction — used by {@link ExpenseClaimService} to recover
   * the loser of a concurrent idempotency-key insert race after its own transition transaction
   * aborted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<ExpenseClaim> findByIdForReplay(UUID claimId) {
    return claimRepository.findById(claimId);
  }

  private void appendEvent(
      ExpenseClaim claim,
      String action,
      ClaimStatus from,
      String actor,
      String comment,
      String idempotencyKey,
      String tenant) {
    ExpenseClaimEvent event =
        new ExpenseClaimEvent(
            claim.getId(), action, from, claim.getStatus(), actor, comment, idempotencyKey);
    event.setCompanyId(tenant);
    eventRepository.saveAndFlush(event);
  }

  private ExpenseCategory requireActiveCategory(UUID categoryId) {
    return categoryRepository
        .findById(categoryId)
        .filter(ExpenseCategory::isActive)
        .orElseThrow(() -> new CategoryNotFoundException(categoryId));
  }

  private ExpenseClaim requireOwnClaim(UUID claimId, UUID employeeId) {
    return claimRepository
        .findById(claimId)
        .filter(c -> c.getEmployeeId().equals(employeeId))
        .orElseThrow(() -> new ClaimNotFoundException(claimId));
  }

  /**
   * The employee's active assignment org unit AS OF {@code expenseDate} — the first (concurrent
   * assignments are rare; "primary" per ADR 0030) match, or {@link
   * PayrollRunWriter#UNALLOCATED_OUTLET} when none overlaps (the {@code
   * PayrollRunWriter.outletsForEmployee} idiom, REUSING its existing all-zeros sentinel rather than
   * defining a second one — money/attribution is never silently dropped, only visibly
   * unattributed).
   */
  private UUID resolveOrgUnitSnapshot(UUID employeeId, LocalDate expenseDate) {
    for (Assignment assignment : assignmentRepository.findByEmployeeId(employeeId)) {
      if (assignment.overlaps(expenseDate, expenseDate)) {
        return assignment.getOrgUnitId();
      }
    }
    return PayrollRunWriter.UNALLOCATED_OUTLET;
  }

  /** See the class Javadoc's "Currency (v1 boundary)" note. */
  private void requireConsistentCurrency(Money amount) {
    claimRepository
        .findAnyCurrency()
        .ifPresent(
            existing -> {
              String establishedCode = existing.strip();
              if (!establishedCode.equals(amount.currency().getCurrencyCode())) {
                throw new IllegalArgumentException(
                    "expense claim currency "
                        + amount.currency().getCurrencyCode()
                        + " does not match this tenant's established expense-claim currency "
                        + establishedCode);
              }
            });
  }

  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }
}
