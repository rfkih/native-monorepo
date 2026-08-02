package id.co.nativeapp.employee.payroll.domain;

import java.util.UUID;

/**
 * A payroll run referenced by id is not visible in the bound tenant (an unknown id, or — invisible
 * under RLS — another tenant's). Mapped to 404 with a generic detail (no existence disclosure),
 * mirroring the other {@code *NotFoundException}s in this service.
 */
public class PayrollRunNotFoundException extends RuntimeException {

  public PayrollRunNotFoundException(UUID runId) {
    super("no such payroll run is accessible: " + runId);
  }
}
