package id.co.nativeapp.org.devicecredential.config;

import id.co.nativeapp.org.devicecredential.service.DeviceCredentialAlreadyExistsException;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialNotFoundException;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialTargetNotOutletException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC-7807 {@link ProblemDetail} advice for the device-credential feature exceptions.
 *
 * <p>The org-unit-not-found case (unknown/cross-tenant outlet id) is deliberately NOT handled here
 * — {@link id.co.nativeapp.org.user.service.OrgUnitNotFoundException} is already mapped to {@code
 * 404} by the sibling {@code UserExceptionAdvice}, so re-declaring it here would only duplicate the
 * mapping.
 */
@RestControllerAdvice
public class DeviceCredentialExceptionAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** A create for an outlet that already has a device credential → {@code 409 Conflict}. */
  @ExceptionHandler(DeviceCredentialAlreadyExistsException.class)
  public ProblemDetail handleAlreadyExists(
      DeviceCredentialAlreadyExistsException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "device-credential-already-exists"));
    problem.setTitle("Conflict");
    problem.setDetail(
        "A device credential already exists for this outlet. Reset it instead of creating a new"
            + " one.");
    problem.setInstance(URI.create(request.getRequestURI()));
    addTraceId(problem);
    return problem;
  }

  /**
   * A reset/reveal/delete targeting an outlet with no device credential → {@code 404 Not Found}.
   */
  @ExceptionHandler(DeviceCredentialNotFoundException.class)
  public ProblemDetail handleNotFound(
      DeviceCredentialNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "device-credential-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No device credential exists for this outlet.");
    problem.setInstance(URI.create(request.getRequestURI()));
    addTraceId(problem);
    return problem;
  }

  /** A create targeting a non-OUTLET org unit → {@code 400 Bad Request}. */
  @ExceptionHandler(DeviceCredentialTargetNotOutletException.class)
  public ProblemDetail handleTargetNotOutlet(
      DeviceCredentialTargetNotOutletException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create(TYPE_BASE + "device-credential-target-not-outlet"));
    problem.setTitle("Bad Request");
    problem.setDetail("A device credential can only be created for an OUTLET-type org unit.");
    problem.setInstance(URI.create(request.getRequestURI()));
    addTraceId(problem);
    return problem;
  }

  private static void addTraceId(ProblemDetail problem) {
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
  }
}
