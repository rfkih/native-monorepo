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
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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
    // A fixed detail: the declared header is client input and is never reflected back.
    problem.setDetail("Only JPEG, PNG, WEBP or PDF are accepted.");
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

  /**
   * A multipart without the {@code file} part, or a body that is not multipart → 400/415, not 500.
   */
  @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
  public ProblemDetail handleBadMultipart(Exception ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "bill-attachment-malformed", request);
    problem.setTitle("Malformed upload");
    problem.setDetail("Send the attachment as a multipart form with one 'file' part.");
    return problem;
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ProblemDetail handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", request);
    problem.setTitle("Unsupported media type");
    problem.setDetail("Send the attachment as multipart/form-data.");
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
