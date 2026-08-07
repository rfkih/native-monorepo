package id.co.nativeapp.payment.config;

import id.co.nativeapp.payment.charge.domain.ChargeConflictException;
import id.co.nativeapp.payment.charge.domain.ChargeNotFoundException;
import id.co.nativeapp.payment.charge.domain.ChargeStateException;
import id.co.nativeapp.payment.charge.domain.ChargeValidationException;
import id.co.nativeapp.payment.charge.domain.GatewayUnavailableException;
import id.co.nativeapp.payment.settings.domain.InvalidQrImageException;
import id.co.nativeapp.payment.settings.domain.PaymentSettingsNotFoundException;
import id.co.nativeapp.payment.settings.domain.SettingsForbiddenException;
import id.co.nativeapp.payment.settings.domain.SettingsValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The payment-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice (ADR 0045) — the fault shapes
 * the shared {@code libs/security ApiExceptionHandler} (validation / {@code
 * IllegalArgumentException} / the non-leaking catch-all) does not own. Mirrors loyalty-service's
 * {@code LoyaltyAdvice} style.
 *
 * <ul>
 *   <li>{@link SettingsForbiddenException} — a non-owner settings write → {@code 403}.
 *   <li>{@link SettingsValidationException} — an unknown mode/provider/environment or an invalid
 *       field combination → {@code 422}.
 *   <li>{@link InvalidQrImageException} — an upload whose actual bytes match no whitelisted image
 *       signature, or disagree with the declared content type → {@code 422}.
 *   <li>{@link PaymentSettingsNotFoundException} — a missing override/image on delete or image
 *       serve → {@code 404}.
 *   <li>{@link MaxUploadSizeExceededException} — an oversized static-QR upload (the writer's
 *       defense-in-depth re-check; Spring's multipart resolver throws the same for an oversized
 *       part) → {@code 413}.
 * </ul>
 */
@RestControllerAdvice
public class PaymentAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(SettingsForbiddenException.class)
  public ProblemDetail handleForbidden(SettingsForbiddenException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(TYPE_BASE + "payment-settings-write-forbidden"));
    problem.setTitle("Forbidden");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(SettingsValidationException.class)
  public ProblemDetail handleValidation(
      SettingsValidationException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "payment-settings-invalid"));
    problem.setTitle("Invalid payment settings request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(InvalidQrImageException.class)
  public ProblemDetail handleInvalidImage(InvalidQrImageException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "qris-image-invalid"));
    problem.setTitle("Invalid QRIS image");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(PaymentSettingsNotFoundException.class)
  public ProblemDetail handleNotFound(
      PaymentSettingsNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "payment-settings-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleOversized(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    // 413 literal — HttpStatus.PAYLOAD_TOO_LARGE is deprecated in Framework 7 (renamed).
    ProblemDetail problem = ProblemDetail.forStatus(413);
    problem.setType(URI.create(TYPE_BASE + "qris-image-too-large"));
    problem.setTitle("Upload too large");
    problem.setDetail("The QRIS image exceeds the 2 MiB limit.");
    return decorate(problem, request);
  }

  @ExceptionHandler(ChargeNotFoundException.class)
  public ProblemDetail handleChargeNotFound(
      ChargeNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "payment-charge-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler({ChargeConflictException.class, ChargeStateException.class})
  public ProblemDetail handleChargeConflict(RuntimeException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "payment-charge-conflict"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(ChargeValidationException.class)
  public ProblemDetail handleChargeValidation(
      ChargeValidationException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "payment-charge-invalid"));
    problem.setTitle("Invalid charge request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(id.co.nativeapp.payment.charge.domain.WebhookRejectedException.class)
  public ProblemDetail handleWebhookRejected(
      id.co.nativeapp.payment.charge.domain.WebhookRejectedException ex,
      HttpServletRequest request) {
    // UNIFORM 401 — deliberately NO detail and NO type distinction: the anonymous caller must not
    // learn whether the tenant, the body, or the signature failed (no oracle).
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    problem.setTitle("Unauthorized");
    return decorate(problem, request);
  }

  @ExceptionHandler(GatewayUnavailableException.class)
  public ProblemDetail handleGatewayUnavailable(
      GatewayUnavailableException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
    problem.setType(URI.create(TYPE_BASE + "psp-unavailable"));
    problem.setTitle("Payment gateway unavailable");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  private static ProblemDetail decorate(ProblemDetail problem, HttpServletRequest request) {
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
