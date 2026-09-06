package id.co.nativeapp.restaurant.integrity.domain;

/**
 * How much attention a leak signal deserves. Deliberately coarse — three levels an owner can act
 * on, not a score. A number would imply a precision this evidence does not have, and would invite
 * ranking two signals against each other that are not commensurable.
 */
public enum LeakSeverity {
  /** Hard to explain innocently, and quantified. Look at this first. */
  HIGH,
  /** Real, but with ordinary explanations available. Worth asking about. */
  MEDIUM,
  /** Hygiene, or a pattern that is only suspicious in combination with something else. */
  LOW
}
