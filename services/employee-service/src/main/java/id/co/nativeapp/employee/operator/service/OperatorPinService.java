package id.co.nativeapp.employee.operator.service;

import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Sets/resets an employee's operator PIN for the bound company (ADR 0049 P1, owner/manager only —
 * gated at the gateway under {@code /api/v1/employees/**} DASHBOARD_ROLES). It orchestrates the
 * transactional unit of work in {@link OperatorPinWriter}; the transaction boundary and RLS GUC
 * both live on the writer so the Spring proxy + the auto-RLS aspect engage (the {@code *Writer}
 * pattern).
 *
 * <p>The PIN it passes through is never logged here (rule 6).
 */
@Service
public class OperatorPinService {

  private final OperatorPinWriter writer;

  public OperatorPinService(OperatorPinWriter writer) {
    this.writer = writer;
  }

  /** Sets or resets the operator PIN for an employee under the bound company. */
  public void setPin(UUID employeeId, String plaintextPin) {
    TenantContext.require();
    writer.setPin(employeeId, plaintextPin);
  }
}
