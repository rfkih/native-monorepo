package id.co.nativeapp.employee.timeoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.timeoff.domain.DayKind;
import id.co.nativeapp.employee.timeoff.domain.DecisionCommentRequiredException;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntry;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStateException;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-JUnit domain logic proofs for {@link OvertimeEntry} — mirrors {@code LeaveRequestTest}. */
class OvertimeEntryTest {

  private static final UUID EMPLOYEE = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final LocalDate WORK_DATE = LocalDate.of(2026, 8, 10);

  private static OvertimeEntry newEntry() {
    return new OvertimeEntry(EMPLOYEE, WORK_DATE, 120, DayKind.WEEKDAY, "idem-key-1");
  }

  @Test
  void aFreshEntryIsSubmittedWithTheGivenFields() {
    OvertimeEntry entry = newEntry();
    assertThat(entry.getStatus()).isEqualTo(TimeoffStatus.SUBMITTED);
    assertThat(entry.getEmployeeId()).isEqualTo(EMPLOYEE);
    assertThat(entry.getWorkDate()).isEqualTo(WORK_DATE);
    assertThat(entry.getMinutes()).isEqualTo(120);
    assertThat(entry.getDayKind()).isEqualTo(DayKind.WEEKDAY);
    assertThat(entry.getIdempotencyKey()).isEqualTo("idem-key-1");
  }

  @Test
  void zeroOrNegativeMinutesIsRejected() {
    assertThatThrownBy(() -> new OvertimeEntry(EMPLOYEE, WORK_DATE, 0, DayKind.WEEKDAY, "k"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OvertimeEntry(EMPLOYEE, WORK_DATE, -1, DayKind.WEEKDAY, "k"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void minutesAboveTheCapIsRejected() {
    assertThatThrownBy(
            () ->
                new OvertimeEntry(
                    EMPLOYEE, WORK_DATE, OvertimeEntry.MAX_MINUTES + 1, DayKind.WEEKDAY, "k"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void minutesAtTheCapIsAccepted() {
    OvertimeEntry entry =
        new OvertimeEntry(EMPLOYEE, WORK_DATE, OvertimeEntry.MAX_MINUTES, DayKind.REST_DAY, "k");
    assertThat(entry.getMinutes()).isEqualTo(OvertimeEntry.MAX_MINUTES);
  }

  @Test
  void approveFromSubmittedTransitionsToApprovedAndStampsTheDecision() {
    OvertimeEntry entry = newEntry();
    Instant now = Instant.parse("2026-08-01T09:00:00Z");
    entry.approve("manager-sub", "ok", now);

    assertThat(entry.getStatus()).isEqualTo(TimeoffStatus.APPROVED);
    assertThat(entry.getDecidedBy()).isEqualTo("manager-sub");
    assertThat(entry.getDecidedAt()).isEqualTo(now);
    assertThat(entry.getDecisionNote()).isEqualTo("ok");
  }

  @Test
  void approveANonSubmittedEntryThrows() {
    OvertimeEntry entry = newEntry();
    entry.approve("manager-sub", null, Instant.now());
    assertThatThrownBy(() -> entry.approve("manager-sub", null, Instant.now()))
        .isInstanceOf(TimeoffStateException.class);
  }

  @Test
  void rejectWithABlankNoteThrows() {
    OvertimeEntry entry = newEntry();
    assertThatThrownBy(() -> entry.reject("manager-sub", "", Instant.now()))
        .isInstanceOf(DecisionCommentRequiredException.class);
  }

  @Test
  void rejectFromSubmittedTransitionsToRejectedAndStampsTheDecision() {
    OvertimeEntry entry = newEntry();
    Instant now = Instant.parse("2026-08-01T09:00:00Z");
    entry.reject("manager-sub", "not approved", now);

    assertThat(entry.getStatus()).isEqualTo(TimeoffStatus.REJECTED);
    assertThat(entry.getDecidedBy()).isEqualTo("manager-sub");
    assertThat(entry.getDecidedAt()).isEqualTo(now);
    assertThat(entry.getDecisionNote()).isEqualTo("not approved");
  }

  @Test
  void cancelFromSubmittedTransitionsToCancelled() {
    OvertimeEntry entry = newEntry();
    entry.cancel();
    assertThat(entry.getStatus()).isEqualTo(TimeoffStatus.CANCELLED);
  }

  @Test
  void cancelAnAlreadyDecidedEntryThrows() {
    OvertimeEntry entry = newEntry();
    entry.approve("manager-sub", null, Instant.now());
    assertThatThrownBy(entry::cancel).isInstanceOf(TimeoffStateException.class);
  }
}
