package id.co.nativeapp.barbershop.catalog.projection;

import java.util.UUID;

/**
 * Read projection over a {@code staff_profile} row — only the columns a {@link
 * id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse StaffProfileResponse} needs.
 */
public interface StaffProfileView {

  UUID getId();

  UUID getBusinessId();

  String getDisplayLabel();

  UUID getEmployeeId();

  boolean isActive();
}
