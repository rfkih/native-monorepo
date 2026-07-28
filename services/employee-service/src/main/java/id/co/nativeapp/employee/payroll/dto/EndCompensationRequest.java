package id.co.nativeapp.employee.payroll.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Request body for {@code PATCH /api/v1/employees/{eid}/compensation/{id}} — ends an OPEN package
 * on a date (effective-dated close; the "change salary" flow is end-old + create-new).
 *
 * @param endOn the package's last effective day; must be on or after its {@code effectiveFrom}
 */
public record EndCompensationRequest(@NotNull LocalDate endOn) {}
