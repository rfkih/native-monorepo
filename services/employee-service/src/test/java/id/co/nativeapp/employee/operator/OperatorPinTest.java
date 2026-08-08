package id.co.nativeapp.employee.operator;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.operator.domain.OperatorPin;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit domain logic proofs for {@link OperatorPin} (ADR 0049 P1) — no Spring context: the
 * lockout invariant lives entirely on the aggregate.
 */
class OperatorPinTest {

  private static final UUID EMPLOYEE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final Instant NOW = Instant.parse("2026-08-08T10:00:00Z");

  @Test
  void aFreshPinIsNotLocked() {
    OperatorPin pin = new OperatorPin(EMPLOYEE_ID, "argon2-hash");

    assertThat(pin.isLocked(NOW)).isFalse();
    assertThat(pin.getFailedAttempts()).isZero();
    assertThat(pin.getLockedUntil()).isNull();
  }

  @Test
  void fewerThanTheThresholdFailedAttemptsDoNotLock() {
    OperatorPin pin = new OperatorPin(EMPLOYEE_ID, "argon2-hash");

    for (int i = 0; i < OperatorPin.MAX_FAILED_ATTEMPTS - 1; i++) {
      pin.recordFailedAttempt(NOW);
    }

    assertThat(pin.getFailedAttempts()).isEqualTo(OperatorPin.MAX_FAILED_ATTEMPTS - 1);
    assertThat(pin.isLocked(NOW)).isFalse();
  }

  @Test
  void reachingTheThresholdLocksForTheConfiguredDuration() {
    OperatorPin pin = new OperatorPin(EMPLOYEE_ID, "argon2-hash");

    for (int i = 0; i < OperatorPin.MAX_FAILED_ATTEMPTS; i++) {
      pin.recordFailedAttempt(NOW);
    }

    assertThat(pin.isLocked(NOW)).isTrue();
    assertThat(pin.isLocked(NOW.plus(OperatorPin.LOCK_DURATION).minusSeconds(1))).isTrue();
    assertThat(pin.isLocked(NOW.plus(OperatorPin.LOCK_DURATION).plusSeconds(1))).isFalse();
  }

  @Test
  void aSuccessfulVerificationResetsFailedAttemptsAndAnyLockout() {
    OperatorPin pin = new OperatorPin(EMPLOYEE_ID, "argon2-hash");
    for (int i = 0; i < OperatorPin.MAX_FAILED_ATTEMPTS; i++) {
      pin.recordFailedAttempt(NOW);
    }
    assertThat(pin.isLocked(NOW)).isTrue();

    pin.recordSuccessfulVerification();

    assertThat(pin.getFailedAttempts()).isZero();
    assertThat(pin.getLockedUntil()).isNull();
    assertThat(pin.isLocked(NOW)).isFalse();
  }

  @Test
  void ownerResetReplacesTheHashAndClearsAnyLockout() {
    OperatorPin pin = new OperatorPin(EMPLOYEE_ID, "old-hash");
    for (int i = 0; i < OperatorPin.MAX_FAILED_ATTEMPTS; i++) {
      pin.recordFailedAttempt(NOW);
    }
    assertThat(pin.isLocked(NOW)).isTrue();

    pin.reset("new-hash");

    assertThat(pin.getPinHash()).isEqualTo("new-hash");
    assertThat(pin.getFailedAttempts()).isZero();
    assertThat(pin.isLocked(NOW)).isFalse();
  }

  @Test
  void toStringNeverExposesTheHash() {
    OperatorPin pin = new OperatorPin(EMPLOYEE_ID, "super-secret-argon2-hash");

    assertThat(pin.toString()).doesNotContain("super-secret-argon2-hash");
    assertThat(pin.toString()).contains(EMPLOYEE_ID.toString());
  }

  @Test
  void lockExpiryIsExactlyAtTheConfiguredBoundary() {
    OperatorPin pin = new OperatorPin(EMPLOYEE_ID, "argon2-hash");
    for (int i = 0; i < OperatorPin.MAX_FAILED_ATTEMPTS; i++) {
      pin.recordFailedAttempt(NOW);
    }
    Instant expectedUnlock = NOW.plus(OperatorPin.LOCK_DURATION);

    assertThat(pin.getLockedUntil()).isEqualTo(expectedUnlock);
  }
}
