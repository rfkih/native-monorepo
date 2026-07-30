package id.co.nativeapp.loyalty.member.domain;

/**
 * Thrown when enrollment is attempted for a phone number that already has a member row for the
 * bound tenant ({@code UNIQUE(company_id, phone_hash)}) → {@code 409 Conflict}. Never carries the
 * raw phone in its message (rule 6).
 */
public class DuplicateMemberException extends RuntimeException {

  public DuplicateMemberException() {
    super("A member is already enrolled with this phone number");
  }
}
