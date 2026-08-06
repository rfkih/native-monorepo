package id.co.nativeapp.security;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single, SHARED RFC 7807 {@link ProblemDetail} advice every Native service inherits (#12),
 * mapping faults to {@code application/problem+json} — never an ad-hoc {@code
 * {status,error,message}} map (ENGINEERING-STANDARDS §1.2). Before this it was copied byte-for-byte
 * into every service's {@code config} package; it now lives once in {@code libs/security} (which
 * already owns the web/edge auto-config and the 403 {@code ProblemDetail} {@link
 * TenantBindingFilter} writes) and is contributed by {@link NativeProblemDetailAutoConfiguration},
 * so a service gets it by dependency, not by copy.
 *
 * <p>Every problem carries a stable kebab-case {@code type} URI ({@code
 * https://errors.nativeapp.id/<slug>}), a numeric {@code status}, a {@code title}, the request
 * {@code instance} path, and — when a trace is in flight — a {@code traceId} property read from the
 * SLF4J {@code MDC} (the {@code traceId} key the logging pattern already uses), so no heavy OTel
 * dependency is pulled in just to surface it.
 *
 * <p>Three fault shapes are handled, matching the contract the per-service copies pinned:
 *
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} — a bean-validation failure on a {@code @Valid}
 *       request body → {@code 400} with a machine-readable {@code errors} list of {@code {field,
 *       message}} (one entry per violated constraint), which the React forms map back to each
 *       field;
 *   <li>{@link IllegalArgumentException} — a value the domain rejects, most importantly a bad/blank
 *       ISO-4217 currency code from {@code libs/money} {@code Money.ofMinor} ({@code
 *       Currency.getInstance} throws {@code IllegalArgumentException} for an unknown code) → {@code
 *       400} with the message as {@code detail};
 *   <li>{@link HttpMessageNotReadableException} — a request body that cannot be deserialized at all
 *       (truncated JSON, a field of the wrong type, {@code null} into a primitive — Jackson 3
 *       enables {@code FAIL_ON_NULL_FOR_PRIMITIVES} by default) → {@code 400}: the fault is the
 *       client's, and letting it fall to the catch-all misclassifies it as a server {@code 500}
 *       (found via the signup flow — a body missing a primitive {@code boolean} field 500'd). The
 *       {@code detail} stays generic: Jackson messages embed reference chains and source snippets
 *       that must not leak; and
 *   <li>any other {@link Exception} — an unexpected fault → {@code 500} whose {@code detail} is a
 *       generic, non-leaking message (the real exception is logged server-side with the {@code
 *       traceId}, never returned to the client; HR-6).
 * </ul>
 *
 * <p><strong>Traceable error reference (the 500 path only).</strong> {@link #handleUnexpected} also
 * (a) persists the fault to the {@code error_log} ops table via the shared {@link ErrorInboxWriter}
 * — gated by the {@code native.error-inbox.persist-http-errors} toggle ({@link
 * NativeProblemDetailAutoConfiguration} reads it, default {@code true}) — and (b) returns a
 * user-facing {@code reference} (the W3C trace id already in flight, or a freshly minted one) as
 * both the {@code reference} and (back-compat) {@code traceId} {@code ProblemDetail} properties, so
 * a user can quote it and ops can {@code SELECT * FROM error_log WHERE trace_id = '<reference>'}.
 * The 400 handlers above are the caller's own fault and are deliberately left untouched — they
 * never persist to the inbox.
 *
 * <p><strong>Composability with service-specific advice.</strong> This advice is ordered at {@link
 * Ordered#LOWEST_PRECEDENCE}, so a service that adds its own {@code @RestControllerAdvice} for a
 * narrower exception (e.g. org-service's {@code TenantAccessDeniedException} → {@code 403}, or
 * finance-service's {@code ConstraintViolationException} → {@code 400}) is consulted first; Spring
 * still resolves the most specific {@code @ExceptionHandler} across all advices, so those keep
 * their mappings while this one owns the shared contract and the catch-all.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final ObjectProvider<ErrorInboxWriter> errorInbox;
  private final boolean persistHttpErrors;

  /**
   * Degraded, no-persistence default — equivalent to {@code new ApiExceptionHandler(null, false)}.
   * Kept so a caller that only needs the RFC 7807 mapping (no error-inbox wiring) still compiles;
   * {@link NativeProblemDetailAutoConfiguration} always uses the two-arg constructor below.
   */
  public ApiExceptionHandler() {
    this(null, false);
  }

  /**
   * @param errorInbox the (possibly bean-absent) {@link ErrorInboxWriter} provider; may be {@code
   *     null} when the caller has no error-inbox wiring at all
   * @param persistHttpErrors the {@code native.error-inbox.persist-http-errors} toggle — when
   *     {@code false}, {@link #handleUnexpected} never calls {@link ErrorInboxWriter#record}
   */
  public ApiExceptionHandler(
      ObjectProvider<ErrorInboxWriter> errorInbox, boolean persistHttpErrors) {
    this.errorInbox = errorInbox;
    this.persistHttpErrors = persistHttpErrors;
  }

  /** Bean-validation failures on a {@code @Valid} request body → 400 with an {@code errors[]}. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleInvalidBody(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", request);
    problem.setTitle("Validation failed");
    problem.setDetail("One or more fields are invalid.");
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(ApiExceptionHandler::toFieldError)
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  /** A domain-rejected value (e.g. an unknown ISO-4217 currency from Money) → 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-argument", request);
    problem.setTitle("Invalid argument");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /**
   * An undeserializable request body (truncated JSON, wrong-typed field, null into a primitive) →
   * 400. The detail is deliberately generic — Jackson's message embeds reference chains and source
   * fragments that must not reach the client (HR-6); the real cause is logged at DEBUG.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "malformed-request-body", request);
    problem.setTitle("Malformed request body");
    problem.setDetail("The request body is malformed or has a field of the wrong type.");
    log.debug("Unreadable request body [traceId={}]", MDC.get("traceId"), ex);
    return problem;
  }

  /**
   * Catch-all → 500 that NEVER leaks the exception message or stack trace to the client (PII/secret
   * risk, HR-6). The real fault is logged server-side AND — when {@code persistHttpErrors} is on
   * and an {@link ErrorInboxWriter} bean is available — persisted to the {@code error_log} ops
   * table, so ops can look up the exact occurrence by the same {@code reference} handed back to the
   * caller.
   *
   * <p>The reference is the W3C trace id already bound in the SLF4J {@code MDC} (the logging
   * pattern already carries it); when no trace is in flight one is minted here (a dash-free UUID)
   * and put into the MDC so the log line, the response, and the {@code error_log} row all share it.
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
    // The reference is the in-flight W3C trace id, or a freshly minted one when tracing did not
    // populate MDC (a degraded/untraced request). We deliberately DO NOT write the generated value
    // back into MDC: on Tomcat's reused platform threads a value left behind with no per-request
    // cleanup would leak the reference onto a SUBSEQUENT request's logs / next 500 (security review
    // MEDIUM). Nothing here needs MDC — the reference is passed explicitly to the log call, the
    // ProblemDetail properties, and record().
    String reference = MDC.get("traceId");
    if (reference == null || reference.isBlank()) {
      reference = UUID.randomUUID().toString().replace("-", "");
    }

    ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", request);
    problem.setTitle("Internal Server Error");
    problem.setDetail("An unexpected error occurred. Reference: " + reference);
    problem.setProperty("reference", reference);
    // Set traceId explicitly too (not just via problem()'s MDC read) so the back-compat property
    // matches the reference even in the generated-fallback case where MDC held nothing.
    problem.setProperty("traceId", reference);

    log.error("Unhandled exception [traceId={}]", reference, ex);

    persistToErrorInbox(ex, request, reference);

    return problem;
  }

  /**
   * Persists {@code ex} to the {@code error_log} ops table via the shared {@link ErrorInboxWriter},
   * gated by {@code persistHttpErrors}. A persistence failure must NEVER change the HTTP response:
   * {@link ErrorInboxWriter#record} already swallows its own failures, but the call is wrapped
   * defensively here too in case that contract ever changes.
   */
  private void persistToErrorInbox(Exception ex, HttpServletRequest request, String reference) {
    if (!persistHttpErrors || errorInbox == null) {
      return;
    }
    try {
      ErrorInboxWriter writer = errorInbox.getIfAvailable();
      if (writer == null) {
        return;
      }
      String companyId = TenantContext.currentCompanyId().orElse(null);
      writer.record(ex, "http:" + request.getRequestURI(), companyId, reference);
    } catch (RuntimeException persistenceFailure) {
      log.warn(
          "error-inbox: failed to persist HTTP error [traceId={}]", reference, persistenceFailure);
    }
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

  private static Map<String, String> toFieldError(FieldError error) {
    String message = error.getDefaultMessage();
    return Map.of("field", error.getField(), "message", message == null ? "invalid" : message);
  }
}
