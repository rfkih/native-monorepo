package id.co.nativeapp.employee.timeoff.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The caller's own leave-request list row (native-query read model, {@code
 * LeaveRequestRepository#findMyRequests}) — snake_case column aliases mapped to camelCase accessors
 * (CODE-STRUCTURE §3.3). No PII on this resource.
 */
public interface MyLeaveRequestView {

  UUID getId();

  String getLeaveType();

  LocalDate getStartDate();

  LocalDate getEndDate();

  int getDays();

  String getStatus();

  String getDecidedBy();

  Instant getDecidedAt();

  String getDecisionNote();
}
