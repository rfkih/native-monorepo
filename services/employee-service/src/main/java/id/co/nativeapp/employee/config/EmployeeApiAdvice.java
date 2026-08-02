package id.co.nativeapp.employee.config;

import id.co.nativeapp.employee.assignment.domain.AssignmentAlreadyEndedException;
import id.co.nativeapp.employee.assignment.domain.AssignmentNotFoundException;
import id.co.nativeapp.employee.assignment.domain.ConflictingLegalEmployerException;
import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.domain.UserAlreadyLinkedException;
import id.co.nativeapp.employee.expense.domain.CategoryNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimStateException;
import id.co.nativeapp.employee.expense.domain.DuplicateCategoryNameException;
import id.co.nativeapp.employee.expense.domain.InvalidGlHintException;
import id.co.nativeapp.employee.expense.domain.InvalidReceiptContentTypeException;
import id.co.nativeapp.employee.expense.domain.ReceiptNotFoundException;
import id.co.nativeapp.employee.expense.domain.SelfApprovalException;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.payroll.domain.CommissionNotFoundException;
import id.co.nativeapp.employee.payroll.domain.CompensationAlreadyEndedException;
import id.co.nativeapp.employee.payroll.domain.CompensationNotFoundException;
import id.co.nativeapp.employee.payroll.domain.DuplicateCommissionException;
import id.co.nativeapp.employee.payroll.domain.IncompletePeriodException;
import id.co.nativeapp.employee.payroll.domain.OverlappingCompensationException;
import id.co.nativeapp.employee.payroll.domain.PayrollSetupNotSeededException;
import id.co.nativeapp.employee.payroll.domain.UnknownDatasetVersionException;
import id.co.nativeapp.employee.payroll.domain.UnknownStatutoryRuleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The employee-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice — the fault shapes unique to
 * this service that the SHARED {@code libs/security} {@code ApiExceptionHandler} (which owns the
 * common {@code @Valid}-body validation / illegal-argument / non-leaking catch-all contract) does
 * not handle.
 *
 * <ul>
 *   <li>{@link ConflictingLegalEmployerException} → {@code 409 Conflict}: the same-legal-employer
 *       invariant (ARCHITECTURE.md §2) was violated — the employee already holds a concurrent
 *       assignment under a DIFFERENT legal employer. A {@code 409} (a state conflict), not a {@code
 *       400}, is the right code: the request is well-formed, but it conflicts with the employee's
 *       current assignments.
 *   <li>{@link ConstraintViolationException} → {@code 400}: a bean-validation failure on a request
 *       <em>parameter</em> (e.g. a malformed path/query param under {@code @Validated}), with the
 *       same machine-readable {@code errors} list of {@code {field, message}} the shared
 *       body-validation handler emits.
 *   <li>{@link EmployeeNotFoundException} → {@code 404 Not Found}: the operation references an
 *       employee not visible in the bound tenant (an unknown id, or — invisible under RLS — another
 *       tenant's). A {@code 404} (not a {@code 400}) is the right code: the resource the request
 *       targets does not exist for this caller.
 * </ul>
 *
 * <p>This advice ADDS to the shared one (which is ordered {@code LOWEST_PRECEDENCE}); Spring
 * resolves the most specific {@code @ExceptionHandler} across both, so unknown-argument {@code 400}
 * and the non-leaking catch-all {@code 500} still come from the shared advice. No PII ever reaches
 * a problem detail (rule 6): these handlers carry only structural messages, never an employee's NIK
 * / bank account.
 */
@RestControllerAdvice
public class EmployeeApiAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** A concurrent assignment under a different legal employer → 409 Conflict. */
  @ExceptionHandler(ConflictingLegalEmployerException.class)
  public ProblemDetail handleConflictingLegalEmployer(
      ConflictingLegalEmployerException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "conflicting-legal-employer", request);
    problem.setTitle("Conflicting legal employer");
    // The message names only legal-employer ids (not PII): safe to surface as the detail.
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** A payroll run asked to calculate/post an incomplete period → 409 Conflict. */
  @ExceptionHandler(IncompletePeriodException.class)
  public ProblemDetail handleIncompletePeriod(
      IncompletePeriodException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "incomplete-period", request);
    problem.setTitle("Period not complete");
    // The message names only business-unit ids (UUIDs), never PII (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** Ending an assignment that is no longer open → 409 Conflict. */
  @ExceptionHandler(AssignmentAlreadyEndedException.class)
  public ProblemDetail handleAssignmentAlreadyEnded(
      AssignmentAlreadyEndedException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "assignment-already-ended", request);
    problem.setTitle("Assignment already ended");
    // The message names only the assignment id (a UUID), never PII (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** An operation referencing an assignment not visible for that employee → 404 Not Found. */
  @ExceptionHandler(AssignmentNotFoundException.class)
  public ProblemDetail handleAssignmentNotFound(
      AssignmentNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "assignment-not-found", request);
    problem.setTitle("Assignment not found");
    // The message names only the assignment id (a UUID), never PII (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** Ending a compensation package that is no longer open → 409 Conflict. */
  @ExceptionHandler(CompensationAlreadyEndedException.class)
  public ProblemDetail handleCompensationAlreadyEnded(
      CompensationAlreadyEndedException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "compensation-already-ended", request);
    problem.setTitle("Compensation package already ended");
    // The message names only the package id (a UUID), never an amount (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** A compensation package overlapping an existing one → 409 Conflict (double-pay guard). */
  @ExceptionHandler(OverlappingCompensationException.class)
  public ProblemDetail handleOverlappingCompensation(
      OverlappingCompensationException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "overlapping-compensation", request);
    problem.setTitle("Overlapping compensation package");
    // The message names only ids (UUIDs), never an amount (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** An open commission on the same metric already exists → 409 Conflict. */
  @ExceptionHandler(DuplicateCommissionException.class)
  public ProblemDetail handleDuplicateCommission(
      DuplicateCommissionException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "duplicate-commission", request);
    problem.setTitle("Duplicate commission");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** An operation referencing a commission rule not visible for that employee → 404. */
  @ExceptionHandler(CommissionNotFoundException.class)
  public ProblemDetail handleCommissionNotFound(
      CommissionNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "commission-not-found", request);
    problem.setTitle("Commission not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** An operation referencing a compensation package not visible for that employee → 404. */
  @ExceptionHandler(CompensationNotFoundException.class)
  public ProblemDetail handleCompensationNotFound(
      CompensationNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "compensation-not-found", request);
    problem.setTitle("Compensation package not found");
    // The message names only the package id (a UUID), never an amount (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** A /me call from a login with no employee link → 404 with a distinct problem type. */
  @ExceptionHandler(EmployeeNotLinkedException.class)
  public ProblemDetail handleEmployeeNotLinked(
      EmployeeNotLinkedException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "employee-not-linked", request);
    problem.setTitle("Login not linked to an employee");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** Linking a login that already belongs to another employee → 409 Conflict. */
  @ExceptionHandler(UserAlreadyLinkedException.class)
  public ProblemDetail handleUserAlreadyLinked(
      UserAlreadyLinkedException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "user-already-linked", request);
    problem.setTitle("Login already linked");
    // The message names only ids (a subject id + an employee UUID), never PII (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** An operation referencing an employee not visible in the bound tenant → 404 Not Found. */
  @ExceptionHandler(EmployeeNotFoundException.class)
  public ProblemDetail handleEmployeeNotFound(
      EmployeeNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "employee-not-found", request);
    problem.setTitle("Employee not found");
    // The message names only the employee id (a UUID), never PII (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  // -------------------------------------------------------------------------------------------
  // Statutory setup / official datasets (Track P phase P2, ADR 0031)
  // -------------------------------------------------------------------------------------------

  /** An unknown {@code datasetVersion} on {@code seed-official} → 404 Not Found. */
  @ExceptionHandler(UnknownDatasetVersionException.class)
  public ProblemDetail handleUnknownDatasetVersion(
      UnknownDatasetVersionException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "unknown-dataset-version", request);
    problem.setTitle("Unknown statutory dataset version");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** A rule_key with no currently-open row (PATCH override target) → 404 Not Found. */
  @ExceptionHandler(UnknownStatutoryRuleException.class)
  public ProblemDetail handleUnknownStatutoryRule(
      UnknownStatutoryRuleException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "unknown-statutory-rule", request);
    problem.setTitle("Unknown statutory rule");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** {@code seed-official} called before any statutory rule exists for the tenant → 409. */
  @ExceptionHandler(PayrollSetupNotSeededException.class)
  public ProblemDetail handlePayrollSetupNotSeeded(
      PayrollSetupNotSeededException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "payroll-setup-not-seeded", request);
    problem.setTitle("Payroll setup not seeded");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** Bean-validation failures on a request parameter → 400. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", request);
    problem.setTitle("Validation failed");
    problem.setDetail("One or more parameters are invalid.");
    List<Map<String, String>> errors =
        ex.getConstraintViolations().stream().map(EmployeeApiAdvice::toViolation).toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  /** A required header (e.g. {@code Idempotency-Key}) is missing from the request → 400. */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ProblemDetail handleMissingRequestHeader(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "missing-required-header", request);
    problem.setTitle("Missing required header");
    problem.setDetail("The '" + ex.getHeaderName() + "' header is required.");
    return problem;
  }

  // -------------------------------------------------------------------------------------------
  // Expense claims (ADR 0030)
  // -------------------------------------------------------------------------------------------

  /** An expense claim referenced by id is unknown, or not visible to the caller → 404. */
  @ExceptionHandler(ClaimNotFoundException.class)
  public ProblemDetail handleClaimNotFound(ClaimNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "expense-claim-not-found", request);
    problem.setTitle("Expense claim not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** An expense-claim transition was attempted from a state that does not allow it → 409. */
  @ExceptionHandler(ClaimStateException.class)
  public ProblemDetail handleClaimState(ClaimStateException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "expense-claim-state-conflict", request);
    problem.setTitle("Expense claim state conflict");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** A login attempted to approve their own expense claim → 403 (owners included). */
  @ExceptionHandler(SelfApprovalException.class)
  public ProblemDetail handleSelfApproval(SelfApprovalException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "self-approval-forbidden", request);
    problem.setTitle("Self-approval forbidden");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /**
   * A concurrent conflict on an expense-claims write → 409 rather than a leaked 500 (S1/S2, code
   * review): a raced idempotency-key insert whose {@link ExpenseClaimService} recovery determined
   * was NOT a genuine replay (e.g. the same key reused for a DIFFERENT action on the same claim),
   * or a raced {@code expense_category} name insert past the {@code ExpenseCategoryWriter}
   * pre-check. Mirrors finance's {@code ApAdvice}/{@code ArAdvice} concurrent-conflict mapping. No
   * money/state is double-applied — the losing transaction rolled back; the client may retry.
   */
  @ExceptionHandler({
    DataIntegrityViolationException.class,
    ObjectOptimisticLockingFailureException.class
  })
  public ProblemDetail handleConcurrentConflict(RuntimeException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "expense-concurrent-conflict", request);
    problem.setTitle("Conflict");
    problem.setDetail("The operation conflicted with a concurrent change; please retry.");
    return problem;
  }

  /** An expense category referenced by id is unknown, or inactive on a create/edit path → 404. */
  @ExceptionHandler(CategoryNotFoundException.class)
  public ProblemDetail handleCategoryNotFound(
      CategoryNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "expense-category-not-found", request);
    problem.setTitle("Expense category not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** An expense category was created/updated with a {@code gl_hint} outside the whitelist → 422. */
  @ExceptionHandler(InvalidGlHintException.class)
  public ProblemDetail handleInvalidGlHint(InvalidGlHintException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-gl-hint", request);
    problem.setTitle("Invalid gl_hint");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** An expense category name (case-insensitive) collides with an existing one → 409. */
  @ExceptionHandler(DuplicateCategoryNameException.class)
  public ProblemDetail handleDuplicateCategoryName(
      DuplicateCategoryNameException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.CONFLICT, "duplicate-expense-category-name", request);
    problem.setTitle("Duplicate expense category name");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  // -------------------------------------------------------------------------------------------
  // Expense receipts (ADR 0030 §8, Phase E3)
  // -------------------------------------------------------------------------------------------

  /**
   * A receipt upload exceeded the size cap (5 MB/file, 6 MB/request — {@code application.yml}) →
   * 413. Thrown either by Spring's own multipart resolver (the part itself is too large) or by
   * {@code ReceiptWriter}'s server-side re-check (defense in depth) — same exception type, same
   * mapping, either way.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleMaxUploadSizeExceeded(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.PAYLOAD_TOO_LARGE, "receipt-too-large", request);
    problem.setTitle("Receipt too large");
    problem.setDetail("The uploaded file exceeds the maximum receipt size.");
    return problem;
  }

  /** A receipt's actual bytes disagree with its declared content-type (magic-byte check) → 422. */
  @ExceptionHandler(InvalidReceiptContentTypeException.class)
  public ProblemDetail handleInvalidReceiptContentType(
      InvalidReceiptContentTypeException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-receipt-content-type", request);
    problem.setTitle("Invalid receipt content-type");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** No receipt is on file for a claim (or it is not visible to the caller) → 404. */
  @ExceptionHandler(ReceiptNotFoundException.class)
  public ProblemDetail handleReceiptNotFound(
      ReceiptNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "expense-receipt-not-found", request);
    problem.setTitle("Expense receipt not found");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String slug, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(TYPE_BASE + slug));
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }

  private static Map<String, String> toViolation(ConstraintViolation<?> violation) {
    String message = violation.getMessage();
    return Map.of(
        "field",
        violation.getPropertyPath().toString(),
        "message",
        message == null ? "invalid" : message);
  }
}
