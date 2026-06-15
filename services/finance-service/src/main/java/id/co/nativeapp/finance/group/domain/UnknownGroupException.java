package id.co.nativeapp.finance.group.domain;

/**
 * Thrown when a {@code GroupMembershipChanged} arrives for a group whose {@code GroupDefined}
 * finance has not yet consumed (so the lead — the tenant the {@link GroupMember} write must be
 * bound to — is unknown). This is a TRANSIENT, RETRYABLE condition: events for a group are
 * partitioned on {@code group_id} so {@code GroupDefined} normally precedes membership events, but
 * a rebalance / parallel delivery can momentarily reorder them. It is deliberately NOT registered
 * as a non-retryable poison, so the container retries (then, only after the bounded budget, DLTs) —
 * by which time {@code GroupDefined} has typically landed and the retry resolves the lead. The
 * membership is never projected under an unknown tenant, and never silently dropped.
 */
public class UnknownGroupException extends RuntimeException {

  public UnknownGroupException(String message) {
    super(message);
  }
}
