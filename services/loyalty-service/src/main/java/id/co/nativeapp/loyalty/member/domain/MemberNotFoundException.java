package id.co.nativeapp.loyalty.member.domain;

import java.util.UUID;

/**
 * Thrown when a member id does not resolve — unknown, or belonging to another tenant (RLS makes the
 * two indistinguishable, so no existence disclosure) → {@code 404}.
 */
public class MemberNotFoundException extends RuntimeException {

  /** Not-found by phone lookup — never includes the phone in the message (rule 6). */
  public MemberNotFoundException() {
    super("No such loyalty member is accessible");
  }

  public MemberNotFoundException(UUID memberId) {
    super("No such loyalty member is accessible: " + memberId);
  }
}
