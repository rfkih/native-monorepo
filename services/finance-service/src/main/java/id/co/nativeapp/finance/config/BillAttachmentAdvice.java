package id.co.nativeapp.finance.config;

import id.co.nativeapp.finance.ap.domain.BillAttachmentLimitExceededException;
import id.co.nativeapp.finance.ap.domain.InvalidBillAttachmentException;
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
 * RFC 7807 mapping for AP bill attachments (ADR 0084). Global and highest precedence because {@link
 * MaxUploadSizeExceededException} is raised by the container before any controller runs — a
 * package-scoped advice would not see it (the restaurant precedent).
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
