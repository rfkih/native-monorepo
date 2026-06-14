package id.co.nativeapp.restaurant;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps invalid client input to a {@code 400 Bad Request} with a small JSON error body, so a
 * malformed request never surfaces as an unhandled {@code 500}.
 *
 * <p>Two input-error shapes are handled:
 *
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} — a bean-validation failure on the {@code @Valid}
 *       {@code POST /sales} body (a missing {@code businessId}, blank {@code currency}/{@code
 *       idempotencyKey}, or non-positive {@code amountMinor}); and
 *   <li>{@link IllegalArgumentException} — a value the domain rejects, most importantly a bad/blank
 *       ISO-4217 currency code from {@code libs/money} {@link
 *       id.co.nativeapp.money.Money#ofMinor(long, String) Money.ofMinor} ({@code
 *       Currency.getInstance} throws {@code IllegalArgumentException} for an unknown code).
 * </ul>
 *
 * <p>This advice deliberately does NOT touch the idempotent-retry path: a duplicate record-sale
 * returns {@code 200} from {@link SaleController} and never throws, so it is unaffected. A
 * concurrent-collision conflict is resolved inside {@link SaleService} (the loser re-reads the
 * existing sale and also returns a successful result), so it never reaches this handler either.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /** Bean-validation failures on a {@code @Valid} request body -> 400. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleInvalidBody(MethodArgumentNotValidException ex) {
    // The first field error is enough for a small, stable error body; the full set
    // is summarized in the message so a client can see every offending field.
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .map(ApiExceptionHandler::describe)
            .reduce((a, b) -> a + "; " + b)
            .orElse("validation failed");
    return badRequest(detail);
  }

  /** A domain-rejected value (e.g. an unknown ISO-4217 currency from Money) -> 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
    return badRequest(ex.getMessage());
  }

  private static String describe(FieldError error) {
    return error.getField() + " " + error.getDefaultMessage();
  }

  private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "message", detail == null ? "bad request" : detail));
  }
}
