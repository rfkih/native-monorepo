package id.co.nativeapp.employee.me.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for the caller's payslip index ({@code GET /api/v1/me/payslips}) — run headers
 * only: which runs carry MY lines, when they posted, and how many lines. Deliberately NO amounts
 * (the list stays cheap and decrypt-free; the detail read decrypts the caller's own lines).
 * Snake_case native-query aliases map to these accessors (CODE-STRUCTURE §3.3).
 */
public interface MyPayslipHeaderView {

  UUID getRunId();

  String getPeriod();

  Integer getRunSeq();

  Instant getPostedAt();

  Integer getLineCount();

  Boolean getIllustrative();
}
