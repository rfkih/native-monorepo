package id.co.nativeapp.employee.timeoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.timeoff.domain.DecisionCommentRequiredException;
import id.co.nativeapp.employee.timeoff.domain.LeaveRequest;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStateException;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit domain logic proofs — no Spring context: every legal and illegal transition on {@link
 * LeaveRequest} (ADR 0033 §3), reject-requires-note, and that approve/reject stamp the decision
 * fields.
 */
class LeaveRequestTest {

  private static final UUID EMPLOYEE = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final LocalDate START = LocalDate.of(2026, 8, 10);
  private static final LocalDate END = LocalDate.of(2026, 8, 12);

  private static LeaveRequest newRequest() {
    return new LeaveRequest(EMPLOYEE, LeaveType.ANNUAL, START, END, 3, "idem-key-1");
  }

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  @Test
  void aFreshRequestIsSubmittedWithTheGivenFields() {
    LeaveRequest request = newRequest();
    assertThat(request.getStatus()).isEqualTo(TimeoffStatus.SUBMITTED);
    assertThat(request.getEmployeeId()).isEqualTo(EMPLOYEE);
    assertThat(request.getLeaveType()).isEqualTo(LeaveType.ANNUAL);
    assertThat(request.getStartDate()).isEqualTo(START);
    assertThat(request.getEndDate()).isEqualTo(END);
    assertThat(request.getDays()).isEqualTo(3);
    assertThat(request.getIdempotencyKey()).isEqualTo("idem-key-1");
  }

  @Test
  void endDateBeforeStartDateIsRejected() {
    assertThatThrownBy(
            () -> new LeaveRequest(EMPLOYEE, LeaveType.ANNUAL, START, START.minusDays(1), 1, "k"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroOrNegativeDaysIsRejected() {
    assertThatThrownBy(() -> new LeaveRequest(EMPLOYEE, LeaveType.ANNUAL, START, END, 0, "k"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LeaveRequest(EMPLOYEE, LeaveType.ANNUAL, START, END, -1, "k"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void daysExceedingTheDateSpanIsRejected() {
    // START..END is a 3-day inclusive span; 4 days exceeds it.
    assertThatThrownBy(() -> new LeaveRequest(EMPLOYEE, LeaveType.ANNUAL, START, END, 4, "k"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void daysWithinTheSpanIsAccepted() {
    LeaveRequest request = new LeaveRequest(EMPLOYEE, LeaveType.ANNUAL, START, END, 2, "k");
    assertThat(request.getDays()).isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // approve
  // ---------------------------------------------------------------------------

  @Test
  void approveFromSubmittedTransitionsToApprovedAndStampsTheDecision() {
    LeaveRequest request = newRequest();
    Instant now = Instant.parse("2026-08-01T09:00:00Z");
    request.approve("manager-sub", "ok", now);

    assertThat(request.getStatus()).isEqualTo(TimeoffStatus.APPROVED);
    assertThat(request.getDecidedBy()).isEqualTo("manager-sub");
    assertThat(request.getDecidedAt()).isEqualTo(now);
    assertThat(request.getDecisionNote()).isEqualTo("ok");
  }

  @Test
  void approveWithANullNoteIsAllowed() {
    LeaveRequest request = newRequest();
    request.approve("manager-sub", null, Instant.now());
    assertThat(request.getDecisionNote()).isNull();
  }

  @Test
  void approveANonSubmittedRequestThrows() {
    LeaveRequest request = newRequest();
    request.approve("manager-sub", null, Instant.now());
    assertThatThrownBy(() -> request.approve("manager-sub", null, Instant.now()))
        .isInstanceOf(TimeoffStateException.class);
  }

  // ---------------------------------------------------------------------------
  // reject
  // ---------------------------------------------------------------------------

  @Test
  void rejectFromSubmittedTransitionsToRejectedAndStampsTheDecision() {
    LeaveRequest request = newRequest();
    Instant now = Instant.parse("2026-08-01T09:00:00Z");
    request.reject("manager-sub", "not enough notice", now);

    assertThat(request.getStatus()).isEqualTo(TimeoffStatus.REJECTED);
    assertThat(request.getDecidedBy()).isEqualTo("manager-sub");
    assertThat(request.getDecidedAt()).isEqualTo(now);
    assertThat(request.getDecisionNote()).isEqualTo("not enough notice");
  }

  @Test
  void rejectWithABlankNoteThrows() {
    LeaveRequest request = newRequest();
    assertThatThrownBy(() -> request.reject("manager-sub", "  ", Instant.now()))
        .isInstanceOf(DecisionCommentRequiredException.class);
    assertThatThrownBy(() -> request.reject("manager-sub", null, Instant.now()))
        .isInstanceOf(DecisionCommentRequiredException.class);
  }

  @Test
  void rejectANonSubmittedRequestThrows() {
    LeaveRequest request = newRequest();
    request.cancel();
    assertThatThrownBy(() -> request.reject("manager-sub", "note", Instant.now()))
        .isInstanceOf(TimeoffStateException.class);
  }

  // ---------------------------------------------------------------------------
  // cancel
  // ---------------------------------------------------------------------------

  @Test
  void cancelFromSubmittedTransitionsToCancelled() {
    LeaveRequest request = newRequest();
    request.cancel();
    assertThat(request.getStatus()).isEqualTo(TimeoffStatus.CANCELLED);
  }

  @Test
  void cancelAnAlreadyDecidedRequestThrows() {
    LeaveRequest request = newRequest();
    request.approve("manager-sub", null, Instant.now());
    assertThatThrownBy(request::cancel).isInstanceOf(TimeoffStateException.class);
  }
}
