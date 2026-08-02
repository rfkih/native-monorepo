package id.co.nativeapp.employee.timeoff.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The caller's own overtime-entry list row (native-query read model). */
public interface MyOvertimeEntryView {

  UUID getId();

  LocalDate getWorkDate();

  int getMinutes();

  String getDayKind();

  String getStatus();

  String getDecidedBy();

  Instant getDecidedAt();

  String getDecisionNote();
}
