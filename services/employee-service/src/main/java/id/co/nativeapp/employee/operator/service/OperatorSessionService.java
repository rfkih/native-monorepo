package id.co.nativeapp.employee.operator.service;

import id.co.nativeapp.employee.operator.dto.OperatorSessionResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Verifies an operator PIN and mints a signed operator session token for the bound company (ADR
 * 0049 P1, POS surface — gated at the gateway under {@code /api/v1/operators/**} POS_ROLES). It
 * orchestrates the transactional unit of work in {@link OperatorSessionWriter}; the transaction
 * boundary and RLS GUC both live on the writer so the Spring proxy + the auto-RLS aspect engage
 * (the {@code *Writer} pattern) — load-bearing here because a FAILED attempt must still persist its
 * lockout counter.
 */
@Service
public class OperatorSessionService {

  private final OperatorSessionWriter writer;

  public OperatorSessionService(OperatorSessionWriter writer) {
    this.writer = writer;
  }

  /** Verifies {@code (employeeId, pin)} for {@code businessId} and mints a session on success. */
  public OperatorSessionResponse createSession(UUID businessId, UUID employeeId, String pin) {
    TenantContext.require();
    return writer.verifyAndMint(businessId, employeeId, pin);
  }
}
