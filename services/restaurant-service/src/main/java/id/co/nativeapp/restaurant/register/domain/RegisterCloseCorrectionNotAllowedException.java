package id.co.nativeapp.restaurant.register.domain;

/**
 * A register-close correction (ADR 0064) that cannot be applied even though the session is CLOSED:
 * the close is too old to self-correct (recent/unsealed-only policy), or it predates the correction
 * feature and carries no {@code close_event_id} for finance to reverse against. Either way the fix
 * is an accountant's adjusting entry, not an in-app edit. Mapped to {@code 422 Unprocessable
 * Entity} ({@code register-close-correction-not-allowed}) by {@code RegisterAdvice}.
 */
public class RegisterCloseCorrectionNotAllowedException extends RuntimeException {

  public RegisterCloseCorrectionNotAllowedException(String message) {
    super(message);
  }
}
