package id.co.nativeapp.loyalty.earnrule.domain;

/** A type/parameter mismatch on earn-rule creation (e.g. a negative rate) → {@code 422}. */
public class EarnRuleValidationException extends RuntimeException {

  public EarnRuleValidationException(String message) {
    super(message);
  }
}
