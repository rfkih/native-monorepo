package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.bill.domain.BillAttachmentLimitExceededException;
import id.co.nativeapp.restaurant.bill.domain.InvalidBillAttachmentException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Maps bill-attachment upload faults (ADR 0063) to RFC-7807 {@link ProblemDetail}s. Mirrors {@code
 * MenuImageAdvice}'s narrow, feature-scoped style.
 *
 * <ul>
 *   <li>{@link InvalidBillAttachmentException} — bytes match no supported type (jpeg/png/webp/pdf)
 *       or contradict the declared type → {@code 422} ({@code bill-attachment-invalid}).
 *   <li>{@link MaxUploadSizeExceededException} — the multipart part exceeds the 5 MiB cap → {@code
 *       413} ({@code bill-attachment-too-large}). Spring's multipart resolver raises this before
 *       the writer runs; without this handler it would surface as a confusing 500.
 * </ul>
 *
 * <p><strong>Scope caveat:</strong> {@code MaxUploadSizeExceededException} is a container-level
 * fault, so this handler is effectively the service-wide 413 mapper — the {@code
 * bill-attachment-too-large} slug would also be applied to any FUTURE multipart endpoint that
 * overruns the cap. Today the only other multipart surface (menu-image upload) is smaller than this
 * 5 MiB limit and never trips it, so there is no ambiguity; if a second oversize-capable multipart
 * endpoint is added, promote this 413 handler to a shared, feature-neutral advice.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BillAttachmentAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(InvalidBillAttachmentException.class)
  public ProblemDetail handleInvalid(
      InvalidBillAttachmentException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "bill-attachment-invalid", request);
    problem.setTitle("Invalid attachment");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(BillAttachmentLimitExceededException.class)
  public ProblemDetail handleLimit(
      BillAttachmentLimitExceededException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "bill-attachment-limit", request);
    problem.setTitle("Attachment limit reached");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleTooLarge(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.PAYLOAD_TOO_LARGE, "bill-attachment-too-large", request);
    problem.setTitle("Attachment too large");
    problem.setDetail("The attachment exceeds the 5 MiB limit.");
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
}
